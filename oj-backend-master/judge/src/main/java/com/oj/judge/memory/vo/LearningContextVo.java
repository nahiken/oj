package com.oj.judge.memory.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Context 组装结果（契约 0004 §6 / 0006 §6）：P6 的 AI 辅导直接消费这个结构。
 *
 * <p>`skills` 在 P4 恒为空数组，P6 起填 ACTIVE 的 Skill（按匹配度取前 3）。
 */
@Data
public class LearningContextVo {

    private ProblemBrief problem;
    private List<EpisodeBrief> episodes = new ArrayList<>();
    private List<PatternBrief> patterns = new ArrayList<>();
    private List<SkillBrief> skills = new ArrayList<>();
    /**
     * 除本题外的相关题（P7 票 06）。固定 3 条；检索不可用时为空，
     * ai 据此把 SSE `meta.searchUsed` 与落库的 `search_used` 置 false。
     */
    private List<ProblemBrief> relatedProblems = new ArrayList<>();
    private int tokenEstimate;
    private boolean truncated;
    /**
     * 摘要口径：`RULE` = 规则摘要（canonical），`LLM` = 至少有一条 Episode 用了 LLM 摘要。
     * ai 原样透传到 SSE `meta.summarySource`（0006 §3）。
     */
    private String summarySource = SOURCE_RULE;

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_LLM = "LLM";

    @Data
    public static class ProblemBrief {
        private Long id;
        private String title;
        private String difficulty;
        private List<String> tags;
    }

    @Data
    public static class EpisodeBrief {
        private String startedAt;
        private String outcome;
        private long durationMin;
        private int attempts;
        private String summary;
    }

    @Data
    public static class PatternBrief {
        private String type;
        private String description;
        private double confidence;
    }

    /** 注入 prompt 的最小信息：名字、步骤文本、适用标签。 */
    @Data
    public static class SkillBrief {
        private Long id;
        private String name;
        private String content;
        private List<String> tags;
    }
}
