package com.oj.judge.memory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聚合分组键：(uid, problem_id)。problem_id 为 0 表示无题目上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionKey {
    private Long uid;
    private Long problemId;
}
