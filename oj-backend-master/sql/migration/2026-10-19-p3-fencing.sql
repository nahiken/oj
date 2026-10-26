-- P3 fencing token 增量 DDL
-- 契约：docs/design-docs/0003-lease-fencing.md §6
-- 幂等性：本脚本只执行一次；重复执行会报 Duplicate column，属预期。
--
-- 说明：epoch 初始为 0。认领时推进（Redis INCR），写结果时校验 epoch，
-- 保证旧执行者（更小 epoch）写不进已被接管的行。存量行由 Redis 在认领时推进。

ALTER TABLE `problem_submit`
  ADD COLUMN `epoch` bigint NOT NULL DEFAULT 0
    COMMENT 'fencing token，认领时推进，写结果时校验' AFTER `attempt`;
