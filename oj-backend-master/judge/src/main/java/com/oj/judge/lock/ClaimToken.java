package com.oj.judge.lock;

/**
 * 认领身份：{@code workerId + epoch} 的 token 形式。
 *
 * <p>token 是锁值、续租参数与解锁参数的唯一来源。把它收成一个类型，避免
 * 「workerId + '#' + epoch」在 Manager 与 Worker 里各拼一遍、一处漏改就解锁失败（S12）。
 */
public final class ClaimToken {

    private final String workerId;
    private final long epoch;

    private ClaimToken(String workerId, long epoch) {
        this.workerId = workerId;
        this.epoch = epoch;
    }

    public static ClaimToken of(String workerId, long epoch) {
        return new ClaimToken(workerId, epoch);
    }

    /** 线上格式：{@code <workerId>#<epoch>}，与契约 0003 §3 一致 */
    public String value() {
        return workerId + "#" + epoch;
    }

    public String workerId() {
        return workerId;
    }

    public long epoch() {
        return epoch;
    }
}
