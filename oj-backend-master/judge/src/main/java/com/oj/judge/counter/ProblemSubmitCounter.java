package com.oj.judge.counter;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.model.entity.Problem;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 题目提交计数写后置。
 *
 * <p>背景：P1 提交路径对同一道题执行 {@code UPDATE problem SET submit_num = submit_num + 1}，
 * 所有并发提交在同一行上排队。实测 120 并发下该语句的行锁等待占掉大半提交延迟
 * （见 {@code docs/generated/p2-judge-pool.md} 归因）。
 *
 * <p>做法：请求线程只做内存累加，定时任务按题目合并成一条 UPDATE。
 * 计数是展示型字段，允许秒级滞后；判题状态与结果不经过这里。
 *
 * <p>契约：{@code docs/design-docs/0002-judge-pool.md} §2。
 */
@Slf4j
@Component
public class ProblemSubmitCounter {

    private final Map<Long, AtomicLong> pending = new ConcurrentHashMap<>();

    @Resource
    private ProblemMapper problemMapper;

    /** 请求线程调用，不加锁、不落库。 */
    public void increment(Long problemId) {
        if (problemId == null) {
            return;
        }
        pending.computeIfAbsent(problemId, key -> new AtomicLong()).incrementAndGet();
    }

    public int pendingProblems() {
        return pending.size();
    }

    @Scheduled(fixedDelayString = "${oj.judge.async.submit-counter-flush-ms:500}")
    public void flush() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.error("evt=submit.counter.flush_error", e);
        }
    }

    /**
     * 合并写一次。失败时把增量加回去，下一轮重试，不丢计数。
     *
     * @return 成功写入的题目数
     */
    public int flushOnce() {
        int applied = 0;
        Iterator<Map.Entry<Long, AtomicLong>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, AtomicLong> entry = it.next();
            long delta = entry.getValue().getAndSet(0L);
            if (delta <= 0) {
                it.remove();
                continue;
            }
            try {
                problemMapper.update(null, new UpdateWrapper<Problem>()
                        .setSql("submit_num = submit_num + " + delta)
                        .eq("id", entry.getKey()));
                applied++;
            } catch (Exception e) {
                entry.getValue().addAndGet(delta);
                log.error("evt=submit.counter.flush_failed problemId={} delta={}", entry.getKey(), delta, e);
            }
        }
        if (applied > 0) {
            log.debug("evt=submit.counter.flush applied={}", applied);
        }
        return applied;
    }

    @PreDestroy
    public void shutdown() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.error("evt=submit.counter.shutdown_error", e);
        }
    }
}
