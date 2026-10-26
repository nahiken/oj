package com.oj.judge.search.query;

import lombok.Data;

/** 检索请求（契约 0005 §4.1）。字段都可缺省，校验在 service 里做，好让错误码走 400。 */
@Data
public class SearchQueryReq {

    /** 自然语言查询，必填 */
    private String query;

    /** 返回条数，默认 10、上限 50 */
    private Integer topK;

    /** bm25 | dense | hybrid，默认 hybrid */
    private String mode;

    /** 可选语种过滤：en / zh */
    private String lang;
}
