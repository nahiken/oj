package com.oj.judge.memory.support;

import com.oj.judge.model.enums.JudgeInfoEnum;

/**
 * 判题状态 → 中文标签。Episode 摘要与 Pattern 描述共用一份映射，避免两处各写一遍
 * （复审 S5）。
 */
public final class JudgeStatusLabel {

    private JudgeStatusLabel() {
    }

    public static String of(Integer status) {
        if (status == null) {
            return null;
        }
        JudgeInfoEnum value = JudgeInfoEnum.getEnumByValue(status);
        return value == null ? "状态 " + status : value.getMsg();
    }

    /** 从 `payload.judgeStatus` 的字符串形式取标签；脏数据返回「状态 x」。 */
    public static String of(String status) {
        try {
            return of(Integer.valueOf(status));
        } catch (Exception e) {
            return status == null ? null : "状态 " + status;
        }
    }
}
