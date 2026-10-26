package com.oj.judge.memory.event;

/**
 * 学习记忆 MQ 拓扑名（契约 0004 §3）。
 */
public final class LearningMemoryMq {

    public static final String EXCHANGE = "learning.event";
    public static final String QUEUE_EVENT = "learning.event.q";
    public static final String RK_EVENT = "learning.event";

    public static final String DLX = "learning.dlx";
    public static final String QUEUE_DEAD = "learning.event.dead.q";
    public static final String RK_DEAD = "learning.event.dead";

    private LearningMemoryMq() {
    }
}
