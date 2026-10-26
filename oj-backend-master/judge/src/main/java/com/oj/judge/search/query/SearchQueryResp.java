package com.oj.judge.search.query;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 检索响应（契约 0005 §4.1）。`esMs` 与 `tookMs` 分开，超标时能直接归因。 */
@Data
public class SearchQueryResp {

    private String query;
    private String mode;
    private int topK;
    private long total;
    /** ES 往返耗时（hybrid 为两路之和） */
    private long esMs;
    /** 边车 embedding 耗时；bm25 恒为 0，这是 hybrid 拆解里必须单独看的一项 */
    private long embedMs;
    private long tookMs;
    private List<SearchHitVo> hits = new ArrayList<>();
}
