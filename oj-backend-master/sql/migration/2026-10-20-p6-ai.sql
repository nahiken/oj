-- P6: the Skill table and its audit trail.
-- Contract: docs/design-docs/0006-ai-tutor.md, .scratch/p6-ai/spec.md D9.

CREATE TABLE IF NOT EXISTS learning_skill (
  id BIGINT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  scope VARCHAR(16) NOT NULL COMMENT 'PERSONAL / PLATFORM',
  owner_uid BIGINT NULL COMMENT 'PLATFORM 为 NULL',
  status VARCHAR(16) NOT NULL COMMENT 'DRAFT / REVIEW / ACTIVE / DEPRECATED',
  content TEXT NOT NULL COMMENT '步骤序列，纯文本，<=500 字',
  steps_json JSON NULL,
  tags JSON NULL,
  source_episode_id BIGINT NULL,
  model VARCHAR(64) NULL,
  prompt_version VARCHAR(32) NULL,
  reject_reason VARCHAR(255) NULL,
  usage_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_scope_status (scope, status),
  KEY idx_owner (owner_uid),
  KEY idx_source_episode (source_episode_id)
);

CREATE TABLE IF NOT EXISTS learning_skill_audit (
  id BIGINT PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NOT NULL,
  actor_uid BIGINT NOT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_skill (skill_id)
);
