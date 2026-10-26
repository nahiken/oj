-- P0 baseline test data: A+B problem (ASCII only)
-- Usage: mysql -uroot -p -D oj < benchmark/seed.sql
-- Idempotent by title.

INSERT INTO problem
  (title, content, difficulty, tags, answer, submit_num, accepted_num,
   judge_case, judge_config, thumb_num, favour_num, user_id)
SELECT
  'A+B Baseline',
  'Read two integers a and b, then print a+b.',
  'easy',
  '["baseline","a+b"]',
  'Read two integers and print their sum.',
  0, 0,
  '[{"input":"1 2","output":"3"},{"input":"10 20","output":"30"}]',
  '{"timeLimit":1000,"memoryLimit":128}',
  0, 0, 1
WHERE NOT EXISTS (
  SELECT 1 FROM problem WHERE title = 'A+B Baseline'
);
