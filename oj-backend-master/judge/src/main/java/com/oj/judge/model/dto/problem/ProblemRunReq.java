package com.oj.judge.model.dto.problem;

import java.util.List;

import lombok.Data;

/**
 * 在线运行请求（judge 侧 DTO）。
 *
 * <p>为什么不直接复用沙箱 SDK 的 `ExecuteCodeReq`：`problemId` 只用于把「在线运行」这条学习事件
 * 归属到题目，沙箱根本不读它；把 judge 的归属字段塞进共享的沙箱请求契约会模糊两个模块的边界
 * （见 P4 复审 S8）。
 */
@Data
public class ProblemRunReq {

    private List<String> inputList;
    private String code;
    private String language;
    /** 题目 id（可选，0 / null = 无题目上下文） */
    private Long problemId;
}
