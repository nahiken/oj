package com.oj.judge.model.vo.problemsubmit;

import lombok.Data;

/**
 * 判题信息（由ExecuteCodeRes转）
 */
@Data
public class JudgeInfo {

    /** 等待判题 / 判题中，未出结果 */
    public static final int STATUS_PENDING = -1;

    // 状态码
    private Integer status;
    // 程序执行信息
    private String msg;

    // 通过用例数
    private Integer pass;
    // 总用例数
    private Integer total;
    // 消耗内存
    private Long memory;
    // 消耗时间（KB）
    private Long time;

    // 未通过的最后一个用例信息
    private String input;
    private String output;
    private String expectedOutput;

    /**
     * 等待期占位，前端据此显示「排队中/判题中」，避免 judgeInfo 为 null。
     */
    public static JudgeInfo pending(int total, String msg) {
        JudgeInfo info = new JudgeInfo();
        info.setStatus(STATUS_PENDING);
        info.setMsg(msg);
        info.setPass(0);
        info.setTotal(total);
        info.setMemory(0L);
        info.setTime(0L);
        return info;
    }
}
