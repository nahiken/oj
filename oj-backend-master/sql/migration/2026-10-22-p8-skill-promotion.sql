-- P8 ticket 05: Skill 晋升（spec D18）。
--
-- 晋升 = 在同一个表里**新建一条 PLATFORM 行**，`origin_skill_id` 指回个人行，
-- 个人行保持原样（作者仍能继续改自己的版本）。
--
-- `(origin_skill_id, scope)` 唯一索引承担幂等：MySQL 的唯一索引允许多个 NULL，
-- 所以所有个人行（origin_skill_id IS NULL）互不冲突，而同一个 origin 不可能
-- 出现两条 PLATFORM 行——重复审批会撞索引，服务层据此返回已有行。

ALTER TABLE learning_skill
  ADD COLUMN origin_skill_id BIGINT NULL COMMENT '晋升来源的个人 Skill id；个人行为 NULL' AFTER id,
  ADD UNIQUE KEY uk_origin_scope (origin_skill_id, scope),
  ADD KEY idx_origin (origin_skill_id);
