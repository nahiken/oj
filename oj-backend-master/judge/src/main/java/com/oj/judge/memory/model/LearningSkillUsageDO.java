package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/**
 * Skill 注入记录（`learning_skill_usage`）。
 *
 * <p>为什么单独一张表：`usage_count` 只能说明「用了几次」，要回答「用了之后有没有成」
 * 必须保留「哪次注入、哪道题」，才能在 10 分钟窗口内把 AC 归因回 Skill（spec D11）。
 */
@Data
public class LearningSkillUsageDO {

    private Long id;
    private Long uid;
    private Long problemId;
    private Long skillId;
    private Date usedAt;

    /** 0 = 还没被某次 AC 归因；1 = 已归因。保证同一次注入只加一次 success_count。 */
    private Boolean rewarded;
}
