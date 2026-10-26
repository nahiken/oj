package com.oj.judge.memory.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * `POST /learning/ai/session` 请求体（P7 票 01）。
 *
 * <p>为什么要有这张表：`二次 AC 率` 的分母是「辅导前该题已有非 AC 提交」的会话，
 * 没有会话行就没有分母。起止时间、`finishReason` 与 token 数只有 AI 服务知道，
 * 所以由它在流结束时回调写入。
 *
 * <p>uid 不在请求体里：只认 token，避免客户端自报身份。
 */
@Data
public class AiTutoringSessionReq {

    private Long problemId;
    private Integer level;
    private String sessionId;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Boolean searchUsed;
}
