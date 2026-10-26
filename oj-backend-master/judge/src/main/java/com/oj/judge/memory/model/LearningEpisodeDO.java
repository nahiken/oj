package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/**
 * learning_episode 行（契约 0004 §4.2）。
 *
 * <p>不可变：物化后不因迟到事件重算；迟到事件进入新会话。
 */
@Data
public class LearningEpisodeDO {

    public static final String OUTCOME_AC = "AC";
    public static final String OUTCOME_WA = "WA";
    public static final String OUTCOME_EXPLORE = "EXPLORE";

    private Long id;
    private Long uid;
    private Long problemId;
    private Date startedAt;
    private Date endedAt;
    private String outcome;
    private Integer attemptCount;
    /** 会话内 `ac` 事件数（摘要「通过 N 次」用它，不是 outcome 标志位） */
    private Integer acCount;
    private Integer runCount;
    private Integer solutionViews;
    private Integer copyCount;
    private Long dwellMs;
    private Integer eventCount;
    /** 会话事件数超过 `max-events-per-session` 被截断（显式标记，不再静默切分） */
    private Boolean truncated;
    private String summary;
    private String summarySource;
    private Integer summaryVersion;
    private String embeddingRef;
    private Date createdAt;
}
