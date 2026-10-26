-- P5 检索：题库需要区分语种与来源（导入的评估语料 vs 真实题目）
-- 见 docs/design-docs/0005-search.md §2.1

ALTER TABLE problem
    ADD COLUMN lang varchar(8) NOT NULL DEFAULT 'en' COMMENT '语种：en / zh' AFTER tags,
    ADD COLUMN source varchar(16) NOT NULL DEFAULT 'native' COMMENT '来源：native 真实题 / imported 评估语料' AFTER lang;

CREATE INDEX idx_problem_source ON problem (source);
CREATE INDEX idx_problem_update_time ON problem (update_time);
