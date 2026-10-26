package com.oj.judge.memory.event;

/**
 * 一次 AC 真正落库后发出的进程内事件（P6 归因用）。
 *
 * <p>与 {@link LearningEventStoredEvent} 分开：那个是「会话可能要收尾」，
 * 这个是「用户刚通过了一道题」，只有 success_count 归因关心。
 */
public final class LearningAcceptedEvent {

    private final Long uid;
    private final Long problemId;

    public LearningAcceptedEvent(Long uid, Long problemId) {
        this.uid = uid;
        this.problemId = problemId;
    }

    public Long getUid() {
        return uid;
    }

    public Long getProblemId() {
        return problemId;
    }
}
