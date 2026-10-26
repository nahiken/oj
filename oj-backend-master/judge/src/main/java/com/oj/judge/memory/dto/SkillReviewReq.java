package com.oj.judge.memory.dto;

import lombok.Data;

/** 审核请求：approve=true 批准，false 打回（打回必须给 reason）。 */
@Data
public class SkillReviewReq {

    private boolean approve;
    private String reason;
}
