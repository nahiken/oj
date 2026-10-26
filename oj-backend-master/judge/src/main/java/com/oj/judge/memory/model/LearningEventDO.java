package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/**
 * learning_event 行（契约 0004 §3.1）。
 */
@Data
public class LearningEventDO {

    private Long id;
    private String eventId;
    private Long uid;
    private String eventType;
    /** 0 = 无题目上下文 */
    private Long problemId;
    /** 0 = 非提交事件 */
    private Long submitId;
    private String source;
    private String payload;
    private Date occurredAt;
    /** null = 尚未被任何 Episode 消费 */
    private Long episodeId;
    private Date createdAt;
}
