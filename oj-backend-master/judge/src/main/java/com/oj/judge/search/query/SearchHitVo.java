package com.oj.judge.search.query;

import java.util.List;

import lombok.Data;

/**
 * 检索结果的一条（契约 0005 §4.1）。
 *
 * <p>**没有 `content` 全文、没有 `answer`、没有 `judgeCase`**：索引里就没有答案，
 * 这个 VO 是第二道结构性保证。
 */
@Data
public class SearchHitVo {

    private Long problemId;
    private String title;
    /** BM25 走 ES highlight（约 150 字，含 `<em>`）；dense/hybrid 无查询词时退化为正文前缀 */
    private String snippet;
    private List<String> tags;
    private String difficulty;
    /** 该条的主分：bm25 为 BM25 分，dense 为向量相似度，hybrid 为 RRF 融合分 */
    private Double score;
    /** 本路名次，从 1 开始；未命中为 null */
    private Integer bm25Rank;
    private Integer denseRank;
    /** RRF 融合分；单路模式下为 null */
    private Double rrfScore;
}
