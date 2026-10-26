package com.oj.judge.memory.support;

import java.util.ArrayList;
import java.util.List;

import cn.hutool.json.JSONUtil;

/**
 * 题目标签 JSON 的唯一解析入口：`problem.tags` 是 `["数组","哈希"]` 形式的字符串。
 *
 * <p>脏数据返回空列表而不是抛异常：一条坏标签不该让整个用户的归纳失败
 * （Episode Context 与 Pattern 共用，复审 S5）。
 */
public final class ProblemTags {

    private ProblemTags() {
    }

    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSONUtil.toList(json, String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
