-- P6 review: success_count needs an attribution trail (spec D11).
-- One row per (uid, problem, skill) injection; an AC within 10 minutes claims
-- the row (rewarded=1) and bumps learning_skill.success_count exactly once.

CREATE TABLE IF NOT EXISTS learning_skill_usage (
  id BIGINT PRIMARY KEY,
  uid BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  used_at DATETIME(3) NOT NULL,
  rewarded TINYINT NOT NULL DEFAULT 0,
  KEY idx_uid_problem_used (uid, problem_id, used_at),
  KEY idx_skill (skill_id)
);
