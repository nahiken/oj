package com.oj.judge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 判题链路配置。契约见 docs/design-docs/0001-async-judge.md 第 11 节。
 *
 * <p>前缀：{@code oj.judge}
 */
@Data
@Component
@ConfigurationProperties(prefix = "oj.judge")
public class JudgeAsyncProperties {

    /**
     * sync | async。async 为默认；sync 时 Worker 不注册，提交走同步链路（回滚开关）。
     */
    private String mode = "async";

    /**
     * 沙箱调用方式：remote | third-party
     */
    private String apiProvider = "remote";

    private AsyncProps async = new AsyncProps();

    public boolean isAsyncMode() {
        return "async".equalsIgnoreCase(mode);
    }

    @Data
    public static class AsyncProps {
        /** 最大投递次数，超过进死信 */
        private int maxAttempt = 3;
        /** Redis 状态键 TTL（秒），滑动续期 */
        private int stateTtlSeconds = 86400;
        /**
         * 认领租约 TTL（毫秒）：锁的 PX 与状态里的 leaseUntil 一致（P3，契约 0003 §7）。
         *
         * <p>到期即可被接管；判题期间由看门狗续租，所以 TTL 只决定「故障恢复上限」，不决定判题上限。
         *
         * <p>默认 10s 而不是 30s：延迟回队会向上取整到 5s/15s/45s 档，30s 租约只能落进 45s 档，
         * 恢复被拖到 50s（P3 实测）。10s 租约落进 15s 档，恢复约 25s。
         */
        private long leaseTtlMs = 10000L;
        /** 看门狗续租间隔（毫秒），默认 TTL/3：容忍连续两次续租失败 */
        private long leaseRenewIntervalMs = 3000L;
        /** 是否续租。false = 固定租约（P1 行为），用于 A/B 与回滚 */
        private boolean leaseRenewEnabled = true;

        /**
         * 归一化后的续租间隔：{@code max(200ms, min(configured, ttl/3))}。
         *
         * <p>看门狗调度周期与 {@code renewAt} 必须用同一个值（P4）：只按配置值调度而按
         * TTL/3 计算「何时该续」，当 {@code ttl <= interval} 时续租会发生在锁过期之后，
         * 活着的判题被误判丢失。下限 200ms 防止极短 TTL 把调度器打满。
         */
        public long effectiveLeaseRenewIntervalMs() {
            long ttl = Math.max(1L, leaseTtlMs);
            long interval = leaseRenewIntervalMs;
            if (interval <= 0) {
                interval = ttl / 3;
            }
            interval = Math.min(interval, ttl / 3);
            return Math.max(200L, interval);
        }
        /** Worker 并发消费者数。prefetch 由 spring.rabbitmq.listener.simple.prefetch 提供 */
        private int workerConcurrency = 2;
        /** 重试延迟（毫秒），对应三档 TTL 队列 */
        private long[] retryDelaysMs = { 5000L, 15000L, 45000L };
        /** 对账周期（毫秒） */
        private long reconcileDelayMs = 30000L;
        /** WAITING 超过该时长（毫秒）视为未投递成功，重投 */
        private long reconcileStaleWaitingMs = 60000L;
        /** RUNNING 超过该时长（毫秒）视为失联，重置后重投 */
        private long reconcileStaleRunningMs = 300000L;
        /** 发布确认超时（毫秒） */
        private long publishConfirmTimeoutMs = 2000L;
        /** 代码大小上限（字节），problem_submit.code 为 TEXT */
        private int maxCodeBytes = 60000;

        /** 三档监听并发，权重 4:2:1（契约 0002 §4.1） */
        private int highConcurrency = 4;
        private int normalConcurrency = 8;
        private int runConcurrency = 1;

        /** 加权派发器：执行线程数（HIGH+NORMAL+RUN 共享）与每档队列容量（防御性上限） */
        private int submitPoolCore = 8;
        private int submitPoolQueue = 256;
        private int runPoolQueue = 64;

        /**
         * 等待老化（契约 0002 §4.3）：默认 30s 升一档、60s 直接 HIGH。
         *
         * <p>阈值不能设成 5s/15s：正常排队也会命中，会重复晋升并在洪峰时放大消息量
         * （P2 实测 40 条任务 24 条被重复晋升、一次 flood 触发 1528 次）。
         */
        private boolean agingEnabled = true;
        private long agingDelayMs = 2000L;
        private long agingPromoteAfterMs = 30000L;
        private long agingForceHighAfterMs = 60000L;
        private int agingBatch = 100;
    }
}
