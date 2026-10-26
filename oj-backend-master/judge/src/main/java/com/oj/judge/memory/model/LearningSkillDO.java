package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/**
 * learning_skill 行（契约 0006）。
 *
 * <p>Skill 是**可执行步骤序列**，不是统计特征：Pattern 描述「你总在哪错」，
 * Skill 描述「这类题按什么步骤做」。两者不可互换。
 */
@Data
public class LearningSkillDO {

    public static final String SCOPE_PERSONAL = "PERSONAL";
    public static final String SCOPE_PLATFORM = "PLATFORM";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    private Long id;
    /**
     * 晋升来源的个人 Skill id（spec D18）。个人行恒为 null；只有晋升出来的
     * PLATFORM 行才有值，并且 `(origin_skill_id, scope)` 唯一——这就是幂等键。
     */
    private Long originSkillId;
    private String name;
    private String scope;
    private Long ownerUid;
    private String status;
    private String content;
    private String stepsJson;
    private String tags;
    private Long sourceEpisodeId;
    private String model;
    private String promptVersion;
    private String rejectReason;
    private Integer usageCount;
    private Integer successCount;
    private Integer version;
    private Date createdAt;
    private Date updatedAt;
}
