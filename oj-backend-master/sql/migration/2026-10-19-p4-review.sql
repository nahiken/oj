-- P4 复审增量 DDL
-- 契约：docs/design-docs/0004-learning-memory.md §4.2
-- 幂等性：本脚本只执行一次；重复执行会报 Duplicate column，属预期。
--
-- ac_count：摘要里「通过 N 次」需要真实的 ac 事件数，不能用 outcome 标志位代替。
-- truncated：会话事件数超过 max-events-per-session 时被显式截断，不再静默切成两个 Episode。

ALTER TABLE `learning_episode`
  ADD COLUMN `ac_count` int NOT NULL DEFAULT 0
    COMMENT '会话内 ac 事件数（摘要「通过 N 次」依据）' AFTER `attempt_count`,
  ADD COLUMN `truncated` tinyint NOT NULL DEFAULT 0
    COMMENT '会话事件数超过上限被截断；0 = 完整会话' AFTER `event_count`;
