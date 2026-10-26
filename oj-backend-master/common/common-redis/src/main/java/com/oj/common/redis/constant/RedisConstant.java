package com.oj.common.redis.constant;


public interface RedisConstant {
    /**
     * 验证码
     */
    String MAIL_CODE_CACHE_PREFIX = "user:mail:code:";

    /**
     * 用户TOKEN
     */
    String USER_TOKEN_PREFIX = "user:token:";

    /**
     * 签到记录
     */
    String CHECK_IN_FORMAT = "checkin:%d:%s";

    /**
     * 判题实时状态，hash。契约见 docs/design-docs/0001-async-judge.md §6
     */
    String JUDGE_STATE_FORMAT = "judge:state:{%d}";

    /**
     * 判题认领锁
     */
    String JUDGE_LOCK_FORMAT = "judge:lock:{%d}";

    /**
     * 判题 fencing token（P3 使用）
     */
    String JUDGE_EPOCH_FORMAT = "judge:epoch:{%d}";
}
