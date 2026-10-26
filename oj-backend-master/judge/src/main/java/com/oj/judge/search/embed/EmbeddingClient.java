package com.oj.judge.search.embed;

import java.util.List;

/**
 * embedding 调用缝（ADR-0002）。
 *
 * <p>调用方只依赖这个接口：换回 Java 内联实现时只需新增一个实现类，不改
 * {@code SearchQueryService} / {@code ReindexService} / {@code ProblemIndexWriter}。
 * 当前唯一实现是 {@link HttpEmbeddingClient}（Python 边车）。
 */
public interface EmbeddingClient {

    /** 边车实际使用的模型 id，写进索引的 `modelId` 字段。 */
    String modelId();

    /** 单条 query 向量；失败抛 503 / {@code EMBEDDING_UNAVAILABLE}。 */
    float[] embedQuery(String text);

    /** 文档侧批量向量；超时与在线查询分开，见 {@code embedding-index-timeout-ms}。 */
    List<float[]> embedPassages(List<String> texts);
}
