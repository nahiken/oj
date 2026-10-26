package com.oj.judge.mq;

/**
 * 队列、交换机、路由键与消息头常量。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md 第 3、4 节、0002-judge-pool.md §4.4。改名等于破坏契约。
 */
public interface JudgeMqConstants {

    // ---------- 任务入口 ----------
    String EXCHANGE = "judge.exchange";

    String QUEUE_HIGH = "judge.queue.high";
    String QUEUE_NORMAL = "judge.queue.normal";
    String QUEUE_RUN = "judge.queue.run";

    String RK_HIGH = "judge.task.high";
    String RK_NORMAL = "judge.task.normal";
    String RK_RUN = "judge.task.run";

    // ---------- 死信 ----------
    String DLX = "judge.dlx";
    String QUEUE_DEAD = "judge.queue.dead";
    String RK_DEAD = "judge.dead";

    // ---------- 重试 ----------
    // 每档优先级各三档延迟：judge.queue.retry.{high,normal,run}.{5s,15s,45s}
    // TTL 到期后由队列的 x-dead-letter-routing-key 显式送回原优先级任务队列
    String RETRY_EXCHANGE = "judge.retry.exchange";

    // ---------- 消息头 ----------
    String HEADER_MSG_ID = "x-judge-msg-id";
    String HEADER_ATTEMPT = "x-judge-attempt";
    String HEADER_ENQUEUED_AT = "x-judge-enqueued-at";
    String HEADER_TRACE_ID = "x-trace-id";

    // ---------- 优先级 ----------
    String PRIORITY_HIGH = "HIGH";
    String PRIORITY_NORMAL = "NORMAL";
    String PRIORITY_RUN = "RUN";

    /** 从高到低，用于声明顺序与测试遍历。 */
    String[] PRIORITIES = { PRIORITY_HIGH, PRIORITY_NORMAL, PRIORITY_RUN };

    /** 与 TTL 队列严格对应。 */
    long[] RETRY_DELAYS_MS = { 5000L, 15000L, 45000L };

    static String routingKeyOf(String priority) {
        if (PRIORITY_HIGH.equals(priority)) {
            return RK_HIGH;
        }
        if (PRIORITY_RUN.equals(priority)) {
            return RK_RUN;
        }
        return RK_NORMAL;
    }

    /** 归一化：未知/null 一律按 NORMAL，避免消息进了没有消费者的队列。 */
    static String normalizePriority(String priority) {
        if (PRIORITY_HIGH.equals(priority) || PRIORITY_RUN.equals(priority)) {
            return priority;
        }
        return PRIORITY_NORMAL;
    }

    /** 5s / 15s / 45s 三档，向上取档。 */
    static int retryBucketSeconds(long delayMs) {
        if (delayMs > 15000L) {
            return 45;
        }
        if (delayMs > 5000L) {
            return 15;
        }
        return 5;
    }

    static String retryRoutingKey(String priority, long delayMs) {
        return "judge.retry." + normalizePriority(priority).toLowerCase() + "." + retryBucketSeconds(delayMs) + "s";
    }

    static String retryQueueName(String priority, long delayMs) {
        return "judge.queue.retry." + normalizePriority(priority).toLowerCase() + "." + retryBucketSeconds(delayMs) + "s";
    }

    /** 老化：RUN → NORMAL → HIGH。 */
    static String nextPriority(String priority) {
        if (PRIORITY_RUN.equals(priority)) {
            return PRIORITY_NORMAL;
        }
        return PRIORITY_HIGH;
    }
}
