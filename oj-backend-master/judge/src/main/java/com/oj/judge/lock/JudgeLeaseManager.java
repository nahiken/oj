package com.oj.judge.lock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.statemachine.JudgeStateService;

import lombok.extern.slf4j.Slf4j;

/**
 * 认领锁 + 租约：抢锁、续租、解锁都在这里，状态机只认 {@code epoch}。
 *
 * <p>设计要点（契约 0003）：
 * <ul>
 *   <li>锁值 = {@code workerId#epoch}，续租/解锁都比对完整 token，所以「同进程两次认领」也能区分；</li>
 *   <li>先 {@code INCR epoch} 再 {@code SET NX PX}：抢锁失败只会浪费一个 epoch 值，不会破坏单调性；</li>
 *   <li>续租失败 = 租约丢失，调用方必须停止提交结果。</li>
 * </ul>
 *
 * <p>续租脚本同时写 lock 的 TTL 与状态哈希的 {@code leaseUntil}，是有意的双写：租约字段
 * 同时属于锁与状态，拆成两次调用会留下「锁已续、状态仍旧」的窗口（见 0003 §3）。
 */
@Slf4j
@Component
public class JudgeLeaseManager {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private DefaultRedisScript<Long> judgeLeaseRenewScript;
    @Resource
    private JudgeStateService stateService;
    @Resource
    private JudgeAsyncProperties properties;
    @Resource
    private JudgeMetrics metrics;

    /** 本进程持有的活跃租约：续租线程只扫这里，规模 = 在飞判题数 */
    private final Map<Long, JudgeLease> active = new ConcurrentHashMap<>();

    /**
     * 抢锁。失败返回 null（有别的执行者），调用方延迟重投。
     */
    public JudgeLease acquire(long submitId, String workerId) {
        long ttl = properties.getAsync().getLeaseTtlMs();
        long epoch = stateService.nextEpoch(submitId);
        ClaimToken claimToken = ClaimToken.of(workerId, epoch);
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(stateService.lockKey(submitId),
                claimToken.value(), Duration.ofMillis(ttl));
        if (!Boolean.TRUE.equals(ok)) {
            metrics.count("judge.lease.acquire_failed");
            log.debug("evt=judge.lease.busy submitId={} epoch={}", submitId, epoch);
            return null;
        }
        JudgeLease lease = new JudgeLease(submitId, workerId, claimToken.value(), epoch);
        lease.setRenewAt(System.currentTimeMillis() + renewInterval());
        return lease;
    }

    /**
     * 认领成功后登记租约，由看门狗续租。
     */
    public void watch(JudgeLease lease) {
        if (!properties.getAsync().isLeaseRenewEnabled()) {
            return;
        }
        active.put(lease.getSubmitId(), lease);
    }

    /**
     * 续租一次。
     *
     * @return true 表示仍是持有者
     */
    public boolean renew(JudgeLease lease) {
        long now = System.currentTimeMillis();
        long ttl = properties.getAsync().getLeaseTtlMs();
        long until = now + ttl;
        Long result = stringRedisTemplate.execute(judgeLeaseRenewScript,
                List.of(stateService.lockKey(lease.getSubmitId()), stateService.stateKey(lease.getSubmitId())),
                lease.getToken(), String.valueOf(ttl), String.valueOf(now), String.valueOf(until));
        if (result != null && result == 1L) {
            lease.setRenewAt(now + renewInterval());
            metrics.count("judge.lease.renew");
            return true;
        }
        markLost(lease, "token_mismatch");
        return false;
    }

    /**
     * 续租线程每 tick 调用：只处理到期的租约。
     */
    public void renewDue() {
        long now = System.currentTimeMillis();
        for (JudgeLease lease : active.values()) {
            if (lease.isLost() || now < lease.getRenewAt()) {
                continue;
            }
            try {
                renew(lease);
            } catch (Exception e) {
                // Redis 异常同样意味着「无法证明我仍是持有者」，必须按丢失处理（契约 0003 §2.6）：
                // 只记日志不置 lost，调用方会继续提交被 fence 之外的结果，等于放弃租约语义。
                log.error("evt=judge.lease.renew_error submitId={}", lease.getSubmitId(), e);
                markLost(lease, "renew_error");
            }
        }
    }

    /**
     * 停止续租并只删自己的锁。
     */
    public void release(JudgeLease lease) {
        active.remove(lease.getSubmitId(), lease);
        try {
            // 释放逻辑只有一份：stateService.unlock 与他人解锁走同一个脚本（S11）
            stateService.unlock(lease.getSubmitId(), lease.getToken());
        } catch (Exception e) {
            // 释放失败不影响正确性：锁会按 TTL 过期，终态写入另有 epoch 校验
            log.warn("evt=judge.lease.release_failed submitId={} msg={}",
                    lease.getSubmitId(), e.getMessage());
        }
    }

    public int activeCount() {
        return active.size();
    }

    private void markLost(JudgeLease lease, String reason) {
        lease.setLost(true);
        // 不再续：从活跃表移除，避免每 tick 重复打日志
        lease.setRenewAt(Long.MAX_VALUE);
        active.remove(lease.getSubmitId(), lease);
        metrics.count("judge.lease.lost");
        log.warn("evt=judge.lease.lost submitId={} worker={} epoch={} reason={} action=abandon",
                lease.getSubmitId(), lease.getWorkerId(), lease.getEpoch(), reason);
    }

    /** 与看门狗共用同一套归一化，避免两处各自算 TTL/3（P4） */
    private long renewInterval() {
        return properties.getAsync().effectiveLeaseRenewIntervalMs();
    }
}
