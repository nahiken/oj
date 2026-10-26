package com.oj.judge.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 检索配置。契约见 docs/design-docs/0005-search.md。
 *
 * <p>前缀：{@code oj.search}
 */
@Data
@Component
@ConfigurationProperties(prefix = "oj.search")
public class SearchProperties {

    /** 总开关：false 时不建 ES 客户端、检索接口 503（判题/提交/记忆零影响） */
    private boolean enabled = true;

    /** 索引名。mapping 变更时要换名字 + 全量重建 */
    private String indexName = "oj_problem_v1";

    /** ES 地址，本机实验环境只有一个节点 */
    private String[] hosts = { "http://127.0.0.1:9200" };

    /** 连接超时（毫秒）：ES 挂掉时必须快速失败，不能把 Tomcat 线程拖住 */
    private int connectTimeoutMs = 2000;

    /** 读超时（毫秒） */
    private int socketTimeoutMs = 5000;

    /** 单次 bulk 的文档数 */
    private int reindexBatchSize = 200;

    /** topK 缺省值与上限（契约 0005 §4.1） */
    private int defaultTopK = 10;
    private int maxTopK = 50;

    /** RRF：k=60、两路权重 1.0，均为契约值，不在评估集上调参（契约 0005 §4.4） */
    private int rrfK = 60;
    private double rrfWeight = 1.0;
    /** hybrid 时每路取多少候选再融合 */
    private int hybridCandidates = 50;

    /** highlight 片段长度（字符） */
    private int highlightFragmentSize = 150;

    /** embedding 边车（ADR 0002）：dense 一路的唯一外部依赖 */
    private boolean embeddingEnabled = true;
    private String embeddingUrl = "http://127.0.0.1:8025";
    /** 边车超时：超过 2s 视为不可用，不让检索线程被拖住 */
    private int embeddingTimeoutMs = 2000;
    /** 批量回填的超时：32 条 × 512 token 在 CPU 上约 12s，不能用在线查询的 2s */
    private int embeddingIndexTimeoutMs = 180000;
    /** 单次 /embed 的文本条数 */
    private int embeddingBatchSize = 32;
    /** passage 的字符上限（真正的 512 token 截断在边车里做） */
    private int passageMaxChars = 6000;

    /** 水位兜底：首次回看窗口、提交乱序余量、单轮上限（扫描周期由 @Scheduled 占位符读取） */
    private long watermarkLookbackMs = 600000L;
    private long watermarkOverlapMs = 5000L;
    private int watermarkBatch = 500;
}
