-- P4 学习记忆体增量 DDL
-- 契约：docs/design-docs/0004-learning-memory.md §3、§4、§5
--
-- 幂等性：全部使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 说明：problem_id / submit_id 缺失时统一写 0 而不是 NULL——唯一键 (uid, problem_id, started_at)
--       在 NULL 上不生效（MySQL 认为两个 NULL 不相等），会导致同一会话重复物化。

CREATE TABLE IF NOT EXISTS `learning_event` (
  `id`          bigint       NOT NULL COMMENT '雪花主键',
  `event_id`    varchar(64)  NOT NULL COMMENT '幂等键（客户端或服务端生成）',
  `uid`         bigint       NOT NULL COMMENT '用户 id',
  `event_type`  varchar(32)  NOT NULL COMMENT 'view_problem/run/submit/ac/wa/view_solution/copy_code/dwell',
  `problem_id`  bigint       NOT NULL DEFAULT 0 COMMENT '题目 id，0 = 无题目上下文',
  `submit_id`   bigint       NOT NULL DEFAULT 0 COMMENT '提交 id，0 = 非提交事件',
  `source`      varchar(16)  NOT NULL DEFAULT 'SERVER' COMMENT 'SERVER / CLIENT',
  `payload`     json         DEFAULT NULL COMMENT '事件附加信息，上限 2KB',
  `occurred_at` datetime(3)  NOT NULL COMMENT '服务端接收时间（聚合依据）',
  `episode_id`  bigint       DEFAULT NULL COMMENT '被哪次聚合消费；NULL = 未消费',
  `created_at`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_uid_time` (`uid`, `occurred_at`),
  KEY `idx_problem_time` (`problem_id`, `occurred_at`),
  KEY `idx_uncovered` (`episode_id`, `occurred_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'P4 学习事件（原子行为）';

CREATE TABLE IF NOT EXISTS `learning_episode` (
  `id`              bigint      NOT NULL COMMENT '雪花主键',
  `uid`             bigint      NOT NULL COMMENT '用户 id',
  `problem_id`      bigint      NOT NULL DEFAULT 0 COMMENT '题目 id，0 = 无题目上下文',
  `started_at`      datetime(3) NOT NULL COMMENT '会话首事件时间',
  `ended_at`        datetime(3) NOT NULL COMMENT '会话末事件时间',
  `outcome`         varchar(16) NOT NULL COMMENT 'AC / WA / EXPLORE',
  `attempt_count`   int         NOT NULL DEFAULT 0 COMMENT 'submit 次数',
  `ac_count`        int         NOT NULL DEFAULT 0 COMMENT 'ac 事件数（摘要「通过 N 次」依据）',
  `run_count`       int         NOT NULL DEFAULT 0 COMMENT 'run 次数',
  `solution_views`  int         NOT NULL DEFAULT 0 COMMENT '查看题解次数',
  `copy_count`      int         NOT NULL DEFAULT 0 COMMENT '复制代码次数',
  `dwell_ms`        bigint      NOT NULL DEFAULT 0 COMMENT '页面停留累计毫秒',
  `event_count`     int         NOT NULL DEFAULT 0 COMMENT '会话事件总数',
  `truncated`       tinyint     NOT NULL DEFAULT 0 COMMENT '会话事件数超上限被截断',
  `summary`         text COMMENT '规则摘要（summary_source=RULE）',
  `summary_source`  varchar(16) NOT NULL DEFAULT 'RULE' COMMENT 'RULE / LLM（P6）',
  `summary_version` int         NOT NULL DEFAULT 1 COMMENT '摘要版本，重算时 +1',
  `embedding_ref`   varchar(64) DEFAULT NULL COMMENT 'P5：ES 文档 id，MySQL 不存向量',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '物化时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid_problem_start` (`uid`, `problem_id`, `started_at`),
  KEY `idx_uid_time` (`uid`, `started_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'P4 学习片段（一次会话/一道题）';

CREATE TABLE IF NOT EXISTS `learning_pattern` (
  `id`            bigint       NOT NULL COMMENT '雪花主键',
  `uid`           bigint       NOT NULL COMMENT '用户 id',
  `pattern_type`  varchar(32)  NOT NULL COMMENT 'ERROR_FOCUS / WEAK_TAG / ATTEMPT_STYLE',
  `description`   varchar(512) NOT NULL COMMENT '人类可读描述',
  `evidence`      json         DEFAULT NULL COMMENT '支撑该结论的计数',
  `confidence`    decimal(4,3) NOT NULL DEFAULT 0.000 COMMENT '0.000 - 1.000',
  `version`       int          NOT NULL DEFAULT 1 COMMENT '重算次数',
  `embedding_ref` varchar(64)  DEFAULT NULL COMMENT 'P5：ES 文档 id',
  `created_at`    datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid_type` (`uid`, `pattern_type`),
  KEY `idx_type_conf` (`pattern_type`, `confidence`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'P4 学习模式（跨 Episode 归纳）';
