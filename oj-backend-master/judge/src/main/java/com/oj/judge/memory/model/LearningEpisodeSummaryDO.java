package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/** learning_episode_summary 行（ADR 0005）：LLM 摘要与规则摘要分开存。 */
@Data
public class LearningEpisodeSummaryDO {

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_LLM = "LLM";

    private Long id;
    private Long episodeId;
    private String source;
    private String model;
    private String promptVersion;
    private String content;
    private Integer tokenEstimate;
    private Date createdAt;
}
