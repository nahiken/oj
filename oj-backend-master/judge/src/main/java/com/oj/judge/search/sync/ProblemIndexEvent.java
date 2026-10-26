package com.oj.judge.search.sync;

/**
 * 题目变更事件（进程内，契约 0005 §3.1 第一条通路）。
 *
 * <p>只带 id 与原因，不带文档内容：消费者重新读库，避免"事件里的旧快照覆盖新快照"。
 */
public class ProblemIndexEvent {

    private final Long problemId;
    private final String reason;

    public ProblemIndexEvent(Long problemId, String reason) {
        this.problemId = problemId;
        this.reason = reason;
    }

    public Long getProblemId() {
        return problemId;
    }

    public String getReason() {
        return reason;
    }
}
