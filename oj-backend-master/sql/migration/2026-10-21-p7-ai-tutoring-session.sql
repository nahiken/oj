-- P7 ticket 01: a tutoring session must leave a row, because 二次 AC 率 needs a
-- denominator. judge owns the table; ai still holds no DB connection.
--
-- finish_reason is closed on purpose: 'stop' / 'guarded' / 'length' / 'error' /
-- 'aborted'. A stream that the user closed early must not look like a normal
-- finish, otherwise the metric silently counts abandoned tabs as satisfied users.

CREATE TABLE IF NOT EXISTS ai_tutoring_session (
  id BIGINT PRIMARY KEY,
  uid BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  level TINYINT NOT NULL,
  session_id VARCHAR(64) NULL,
  started_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NOT NULL,
  finish_reason VARCHAR(16) NOT NULL,
  prompt_tokens INT NULL,
  completion_tokens INT NULL,
  search_used TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_uid_problem_finished (uid, problem_id, finished_at),
  KEY idx_problem (problem_id),
  KEY idx_finished (finished_at)
);
