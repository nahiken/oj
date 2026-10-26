package com.oj.judge.model.dto.problem;


import java.util.List;

import com.oj.common.mybatis.dto.PageReq;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ProblemQueryReq extends PageReq {
    /**
     * 标签列表
     */
    private List<String> tags;
    private String status;
    private String difficulty;
    private String keyword;
}