package com.oj.judge.memory.model;

import java.util.Date;

import lombok.Data;

/** learning_skill_audit 行：每次状态变化留痕（谁、从什么、到什么、为什么）。 */
@Data
public class LearningSkillAuditDO {

    private Long id;
    private Long skillId;
    private String fromStatus;
    private String toStatus;
    private Long actorUid;
    private String reason;
    private Date createdAt;
}
