package com.oj.judge.memory.event;

import lombok.Getter;

/**
 * 学习事件类型（契约 0004 §2.1）。
 *
 * <p>八类，每类只有一个发射点。前五类由服务端发射；后三类只有浏览器知道，由前端 SDK 上报，
 * 服务端对它们只收不推，并做白名单校验——客户端不能伪造 submit/ac，否则 Episode 的
 * outcome 与 Pattern 的置信度就失去意义。
 */
@Getter
public enum LearningEventType {

    VIEW_PROBLEM("view_problem", false),
    RUN("run", false),
    SUBMIT("submit", false),
    AC("ac", false),
    WA("wa", false),
    VIEW_SOLUTION("view_solution", true),
    COPY_CODE("copy_code", true),
    DWELL("dwell", true);

    private final String value;

    /** 是否允许客户端上报 */
    private final boolean clientReportable;

    LearningEventType(String value, boolean clientReportable) {
        this.value = value;
        this.clientReportable = clientReportable;
    }

    public static LearningEventType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (LearningEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    public static LearningEventType fromClientValue(String value) {
        LearningEventType type = fromValue(value);
        return type != null && type.clientReportable ? type : null;
    }
}
