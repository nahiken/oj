-- P1 异步判题增量 DDL
-- 契约：docs/design-docs/0001-async-judge.md §7
-- 幂等性：本脚本只执行一次；重复执行会报 Duplicate column，属预期。

ALTER TABLE `problem_submit`
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '状态版本号，与 Redis version 对齐，单调递增',
  ADD COLUMN `worker_id` varchar(64) DEFAULT NULL COMMENT '处理该提交的 Worker 标识',
  ADD COLUMN `attempt` int NOT NULL DEFAULT 0 COMMENT '已投递次数',
  ADD COLUMN `enqueued_at` datetime(3) DEFAULT NULL COMMENT '首次入队时间',
  ADD COLUMN `started_at` datetime(3) DEFAULT NULL COMMENT '开始判题时间',
  ADD COLUMN `finished_at` datetime(3) DEFAULT NULL COMMENT '判题结束时间',
  ADD KEY `idx_status_updateTime` (`status`, `update_time`);

ALTER TABLE `problem_submit`
  MODIFY COLUMN `status` int NOT NULL DEFAULT '0'
  COMMENT '判题状态(0 - 等待中、1 - 判题中、2 - 未通过、3 - 通过、4 - 已取消)';
