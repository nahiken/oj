package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/**
 * 一次辅导会话（`ai_tutoring_session`，P7 票 01）。
 *
 * <p>为什么单独一张表：`evt=ai.call` 只是日志，查不到「谁、哪道题、结束在什么时候」。
 * 而 二次 AC 率 需要「会话结束 + 30 分钟内同题 AC」这个时间窗，只能靠库里的行来算。
 *
 * <p>`finish_reason` 区分正常结束与被守卫截断 / 用户中断：把被截断的回答算进采纳率
 * 是没有意义的。
 */
@Data
public class AiTutoringSessionDO {

    private Long id;
    private Long uid;
    private Long problemId;
    private Integer level;

    /** 客户端生成的会话标识，用于把同一题的多轮归并；可为空。 */
    private String sessionId;

    private Date startedAt;
    private Date finishedAt;

    /** stop / guarded / length / error / aborted。 */
    private String finishReason;

    /** 估算值（与 SSE `done.tokens` 同源），不是计费口径。 */
    private Integer promptTokens;
    private Integer completionTokens;

    /** 本次是否注入了 `[相关题]` 段。 */
    private Boolean searchUsed;
}
