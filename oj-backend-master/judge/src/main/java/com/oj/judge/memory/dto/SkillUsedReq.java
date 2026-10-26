package com.oj.judge.memory.dto;

import java.util.List;

import lombok.Data;

/**
 * `POST /learning/skill/used` 请求体。
 *
 * <p>带 `problemId` 不是为了多一个参数，而是为了让「这次注入」能落到具体题目上：
 * 10 分钟内同题 AC 才归因 success_count（spec D11）。
 */
@Data
public class SkillUsedReq {

    private Long problemId;
    private List<Long> ids;
}
