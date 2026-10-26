package com.oj.judge.lock;

import lombok.Getter;
import lombok.Setter;

/**
 * 一次成功认领的租约：token 是「我」的唯一凭证，epoch 是 fencing token。
 *
 * <p>契约：docs/design-docs/0003-lease-fencing.md §3、§5。
 */
@Getter
public class JudgeLease {

    private final long submitId;
    private final String workerId;
    /** workerId#epoch：续租与解锁都要原样带回，锁值也是它 */
    private final String token;
    private final long epoch;

    /** 续租失败即为 true；此后不得再提交结果 */
    @Setter
    private volatile boolean lost;
    /** 下一次该续租的时间（epoch ms）；lost 后置为 Long.MAX_VALUE，避免每 tick 重试 */
    @Setter
    private volatile long renewAt;

    public JudgeLease(long submitId, String workerId, String token, long epoch) {
        this.submitId = submitId;
        this.workerId = workerId;
        this.token = token;
        this.epoch = epoch;
    }
}
