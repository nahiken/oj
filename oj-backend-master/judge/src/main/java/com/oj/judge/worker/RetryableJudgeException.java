package com.oj.judge.worker;

/**
 * 可重试的判题失败：沙箱不可用、依赖服务异常等。业务失败（编译错误、答案错误）不走这里。
 *
 * <p>{@code epoch} 非空表示「抛异常时仍持有这一代租约」，重试/置败迁移要带上它做 fencing，
 * 否则被接管的旧执行者会把别人的 RUNNING 重置回 WAITING（契约 0003 §5.2）。
 */
public class RetryableJudgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long epoch;

    public RetryableJudgeException(String message) {
        this(message, null, null);
    }

    public RetryableJudgeException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public RetryableJudgeException(String message, Throwable cause, Long epoch) {
        super(message, cause);
        this.epoch = epoch;
    }

    public Long getEpoch() {
        return epoch;
    }
}
