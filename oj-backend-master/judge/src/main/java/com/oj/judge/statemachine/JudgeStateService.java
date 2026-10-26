package com.oj.judge.statemachine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.oj.common.redis.constant.RedisConstant;
import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.model.entity.ProblemSubmit;

import lombok.extern.slf4j.Slf4j;

/**
 * 判题状态机：Redis 是迁移的串行化点，MySQL 只做单调版本镜像。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §6、§8。
 */
@Slf4j
@Service
public class JudgeStateService {

    /** 迁移脚本返回值 */
    public static final long OK = 0L;
    public static final long NOT_FOUND = -1L;
    public static final long STATUS_CONFLICT = 1L;
    public static final long VERSION_CONFLICT = 2L;
    /** epoch 与当前持有者不符：写入被 fence（契约 0003 §4.1） */
    public static final long FENCED = 3L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private DefaultRedisScript<Long> judgeInitScript;
    @Resource
    private DefaultRedisScript<Long> judgeTransitionScript;
    @Resource
    private DefaultRedisScript<Long> judgeUnlockScript;
    @Resource
    private DefaultRedisScript<Long> judgeNextEpochScript;
    @Resource
    private JudgeAsyncProperties properties;

    public String stateKey(long submitId) {
        return String.format(RedisConstant.JUDGE_STATE_FORMAT, submitId);
    }

    public String lockKey(long submitId) {
        return String.format(RedisConstant.JUDGE_LOCK_FORMAT, submitId);
    }

    public String epochKey(long submitId) {
        return String.format(RedisConstant.JUDGE_EPOCH_FORMAT, submitId);
    }

    /**
     * 幂等初始化：已存在则不覆盖。
     *
     * @return true 表示本次创建成功
     */
    public boolean initIfAbsent(long submitId, long uid, long problemId, String judgeInfoJson) {
        Long result = stringRedisTemplate.execute(judgeInitScript,
                List.of(stateKey(submitId)),
                String.valueOf(uid),
                String.valueOf(problemId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(properties.getAsync().getStateTtlSeconds()),
                judgeInfoJson == null ? "{}" : judgeInfoJson);
        return result != null && result == OK;
    }

    /**
     * 原子迁移。
     *
     * @param expectedStatus  -1 表示不限（仅对账修复使用）
     * @param expectedVersion -1 表示不限
     * @param leaseUntilMs    为空表示不写
     * @return 0 成功 / -1 Key 不存在 / 1 status 冲突 / 2 version 冲突 / 3 epoch 冲突
     */
    public long transition(long submitId, int expectedStatus, int expectedVersion, int targetStatus,
            String workerId, Integer attempt, String judgeInfoJson, Long leaseUntilMs) {
        return transition(submitId, expectedStatus, expectedVersion, targetStatus, workerId, attempt,
                judgeInfoJson, leaseUntilMs, null, null);
    }

    /**
     * 原子迁移（带 fencing）。
     *
     * @param expectedEpoch 非空表示「只有当前 epoch 的持有者能写」，不符返回 {@link #FENCED}
     * @param newEpoch      非空表示把 epoch 推进到该值（认领与接管）
     */
    public long transition(long submitId, int expectedStatus, int expectedVersion, int targetStatus,
            String workerId, Integer attempt, String judgeInfoJson, Long leaseUntilMs,
            Long expectedEpoch, Long newEpoch) {
        List<String> args = new ArrayList<>(11);
        args.add(String.valueOf(expectedStatus));
        args.add(String.valueOf(expectedVersion));
        args.add(String.valueOf(targetStatus));
        args.add(workerId == null ? "" : workerId);
        args.add(attempt == null ? "" : String.valueOf(attempt));
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add(String.valueOf(properties.getAsync().getStateTtlSeconds()));
        args.add(judgeInfoJson == null ? "" : judgeInfoJson);
        args.add(leaseUntilMs == null ? "" : String.valueOf(leaseUntilMs));
        args.add(expectedEpoch == null ? "" : String.valueOf(expectedEpoch));
        args.add(newEpoch == null ? "" : String.valueOf(newEpoch));
        Long result = stringRedisTemplate.execute(judgeTransitionScript, List.of(stateKey(submitId)),
                args.toArray());
        return result == null ? VERSION_CONFLICT : result;
    }

    public JudgeState getState(long submitId) {
        Map<Object, Object> hash = stringRedisTemplate.opsForHash().entries(stateKey(submitId));
        return JudgeState.fromHash(submitId, hash);
    }

    /**
     * 用 MySQL 的权威状态重建 Redis 快照（Key 丢失或对账时使用）。
     */
    public void cacheFromDb(ProblemSubmit submit) {
        if (submit == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, String> hash = new HashMap<>(12);
        hash.put("status", String.valueOf(submit.getStatus() == null ? 0 : submit.getStatus()));
        hash.put("version", String.valueOf(submit.getVersion() == null ? 0 : submit.getVersion()));
        hash.put("attempt", String.valueOf(submit.getAttempt() == null ? 0 : submit.getAttempt()));
        hash.put("epoch", String.valueOf(submit.getEpoch() == null ? 0L : submit.getEpoch()));
        hash.put("uid", String.valueOf(submit.getUserId() == null ? 0L : submit.getUserId()));
        hash.put("problemId", String.valueOf(submit.getProblemId() == null ? 0L : submit.getProblemId()));
        hash.put("ji", submit.getJudgeInfo() == null ? "{}" : submit.getJudgeInfo());
        hash.put("createdAt", String.valueOf(now));
        hash.put("updatedAt", String.valueOf(now));
        hash.put("dbSynced", "1");
        stringRedisTemplate.opsForHash().putAll(stateKey(submit.getId()), hash);
        stringRedisTemplate.expire(stateKey(submit.getId()),
                Duration.ofSeconds(properties.getAsync().getStateTtlSeconds()));
    }

    public void delete(long submitId) {
        stringRedisTemplate.delete(stateKey(submitId));
    }

    /**
     * 推进 fencing token 并返回新值。不直接写状态哈希：
     * 真正「成为持有者」以一次成功的迁移（带 {@code newEpoch}）为准，见 {@code JudgeLeaseManager}。
     *
     * <p>脚本把「INCR」与「不小于状态哈希 epoch」放在同一个原子点（契约 0003 §2.3）：
     * {@code judge:epoch:*} 被 evict/清理后从 1 重来，也不会让已经用过的 epoch 重新有效。
     */
    public long nextEpoch(long submitId) {
        Long epoch = stringRedisTemplate.execute(judgeNextEpochScript,
                List.of(epochKey(submitId), stateKey(submitId)),
                String.valueOf(properties.getAsync().getStateTtlSeconds()));
        return epoch == null ? 0L : epoch;
    }

    /**
     * 只删自己的锁：token 不匹配时不产生副作用（契约 0003 §4.3）。
     */
    public void unlock(long submitId, String token) {
        stringRedisTemplate.execute(judgeUnlockScript, List.of(lockKey(submitId)), token);
    }
}
