package com.oj.judge.model.dto.problemsubmit;

import com.oj.common.mybatis.dto.PageReq;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProblemSubmitQueryReq extends PageReq {
    /**
     * 题目 id
     */
    private Long problemId;
}