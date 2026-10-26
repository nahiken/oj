-- 二次 AC 率（口径见 GLOSSARY.md；P7 票 11）
--
-- 分母：辅导开始前，同一用户在同一题目上已有**非 AC** 判题结果的辅导会话数。
-- 分子：这些会话结束后 30 分钟内，同一用户在同一题目上出现 AC 的会话数。
--
-- AC 的真相是 judge_info->>'$.status' = '0'，**不是** problem_submit.status：
-- 后者只是 WAITING/RUNNING/FAILED/SUCCEED 的生命周期，WA 与 AC 都是 3。
-- 判定时间用 finished_at（判题出结果的时间），没有则退回 create_time。

SET @uid_from = 9900000101;
SET @uid_to = 9900000112;
SET @window_seconds = 1800;

-- 1) 分母 / 分子 / 比例
SELECT
    COUNT(*) AS denominator,
    SUM(after_ac) AS numerator,
    ROUND(IF(COUNT(*) = 0, 0, SUM(after_ac) / COUNT(*)), 4) AS rate
FROM (
    SELECT
        s.id,
        EXISTS (
            SELECT 1 FROM problem_submit p
            WHERE p.user_id = s.uid
              AND p.problem_id = s.problem_id
              AND p.is_delete = 0
              AND p.judge_info IS NOT NULL
              AND p.judge_info->>'$.status' <> '0'
              AND COALESCE(p.finished_at, p.create_time) < s.started_at
        ) AND EXISTS (
            SELECT 1 FROM problem_submit p
            WHERE p.user_id = s.uid
              AND p.problem_id = s.problem_id
              AND p.is_delete = 0
              AND p.judge_info->>'$.status' = '0'
              AND COALESCE(p.finished_at, p.create_time)
                  BETWEEN s.finished_at
                  AND DATE_ADD(s.finished_at, INTERVAL @window_seconds SECOND)
        ) AS after_ac
    FROM ai_tutoring_session s
    WHERE s.uid BETWEEN @uid_from AND @uid_to
      AND EXISTS (
            SELECT 1 FROM problem_submit p
            WHERE p.user_id = s.uid
              AND p.problem_id = s.problem_id
              AND p.is_delete = 0
              AND p.judge_info IS NOT NULL
              AND p.judge_info->>'$.status' <> '0'
              AND COALESCE(p.finished_at, p.create_time) < s.started_at
      )
) d;

-- 2) 逐会话明细（审计为什么分子是这些）
SELECT
    s.uid,
    s.problem_id,
    s.finish_reason,
    s.started_at,
    s.finished_at,
    (SELECT COUNT(*) FROM problem_submit p
      WHERE p.user_id = s.uid AND p.problem_id = s.problem_id
        AND p.is_delete = 0
        AND p.judge_info IS NOT NULL AND p.judge_info->>'$.status' <> '0'
        AND COALESCE(p.finished_at, p.create_time) < s.started_at) AS prior_non_ac,
    (SELECT COUNT(*) FROM problem_submit p
      WHERE p.user_id = s.uid AND p.problem_id = s.problem_id
        AND p.is_delete = 0
        AND p.judge_info->>'$.status' = '0'
        AND COALESCE(p.finished_at, p.create_time)
            BETWEEN s.finished_at
            AND DATE_ADD(s.finished_at, INTERVAL @window_seconds SECOND)) AS ac_within_window
FROM ai_tutoring_session s
WHERE s.uid BETWEEN @uid_from AND @uid_to
  AND EXISTS (
        SELECT 1 FROM problem_submit p
        WHERE p.user_id = s.uid AND p.problem_id = s.problem_id
          AND p.is_delete = 0 AND p.judge_info IS NOT NULL
          AND p.judge_info->>'$.status' <> '0'
          AND COALESCE(p.finished_at, p.create_time) < s.started_at
  )
ORDER BY s.uid, s.started_at;
