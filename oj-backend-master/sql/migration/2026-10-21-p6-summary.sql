-- P6 S8: LLM summaries live in their own table (ADR 0005).
-- learning_episode.summary stays the canonical RULE summary.

CREATE TABLE IF NOT EXISTS learning_episode_summary (
  id BIGINT PRIMARY KEY,
  episode_id BIGINT NOT NULL,
  source VARCHAR(8) NOT NULL COMMENT 'RULE / LLM',
  model VARCHAR(64) NULL,
  prompt_version VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  token_estimate INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_episode_source_version (episode_id, source, prompt_version)
);
