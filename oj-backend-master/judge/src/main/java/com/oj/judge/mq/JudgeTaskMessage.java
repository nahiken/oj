package com.oj.judge.mq;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判题任务消息 v1（claim-check：不带代码，Worker 回库读取）。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md 第 3 节。字段只增不改语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTaskMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 固定 1，不认识就 ack 丢弃 */
    private Integer schemaVersion;

    /** 每次投递唯一，重试重新生成 */
    private String msgId;

    private Long submitId;

    private Long problemId;

    private Long uid;

    private String language;

    /** 第几次投递，从 1 开始 */
    private Integer attempt;

    /** HIGH / NORMAL / RUN */
    private String priority;

    /** 首次入队时间，重试不刷新 */
    private Long enqueuedAt;

    private String traceId;

    /** 保留字段，v1 必须为 null */
    private String code;

    public static final int SCHEMA_VERSION = 1;
}
