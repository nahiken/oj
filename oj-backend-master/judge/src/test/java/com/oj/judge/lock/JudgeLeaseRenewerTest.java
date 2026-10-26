package com.oj.judge.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.oj.judge.config.JudgeAsyncProperties;

/**
 * P4：看门狗调度周期与 {@code renewAt} 必须共用同一个归一化。
 *
 * <p>这个测试不需要 Redis：它验证的是纯配置换算，Redis 不可用时也必须能跑。
 */
class JudgeLeaseRenewerTest {

    @Test
    void configuredIntervalLongerThanTtlThirdFallsBackToTtlThird() {
        JudgeAsyncProperties properties = properties(2000L, 3000L);
        assertEquals(666L, JudgeLeaseRenewer.intervalMs(properties),
                "ttl=2000, interval=3000 时调度周期必须是 666ms，否则会在锁过期后才续租");
    }

    @Test
    void configuredIntervalShorterThanTtlThirdIsKept() {
        JudgeAsyncProperties properties = properties(10000L, 500L);
        assertEquals(500L, JudgeLeaseRenewer.intervalMs(properties));
    }

    @Test
    void missingIntervalDefaultsToTtlThird() {
        JudgeAsyncProperties properties = properties(10000L, 0L);
        assertEquals(3333L, JudgeLeaseRenewer.intervalMs(properties));
    }

    @Test
    void intervalHasA200msFloor() {
        JudgeAsyncProperties properties = properties(300L, 50L);
        assertEquals(200L, JudgeLeaseRenewer.intervalMs(properties));
    }

    private static JudgeAsyncProperties properties(long ttlMs, long intervalMs) {
        JudgeAsyncProperties properties = new JudgeAsyncProperties();
        properties.getAsync().setLeaseTtlMs(ttlMs);
        properties.getAsync().setLeaseRenewIntervalMs(intervalMs);
        return properties;
    }
}
