-- P2 分级调度增量 DDL
-- 契约：docs/design-docs/0002-judge-pool.md §4.3、§4.5
-- 幂等性：本脚本只执行一次；重复执行会报 Duplicate column，属预期。

ALTER TABLE `problem_submit`
  ADD COLUMN `priority` varchar(16) NOT NULL DEFAULT 'NORMAL'
    COMMENT '判题优先级 HIGH/NORMAL/RUN，老化会晋升' AFTER `status`,
  ADD KEY `idx_status_priority_enqueued` (`status`, `priority`, `enqueued_at`);

-- 存量行默认 NORMAL；队列里的旧消息（schemaVersion=1，无 priority 字段）按 NORMAL 处理。
