package com.oj.judge.memory.skill.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Skill 检索配置（spec D20）。前缀：{@code oj.skill-search}
 *
 * <p>和题目的 {@code oj.search} 分开：两者的索引名、topK 上限、开关都不同，
 * 合成一个配置类会让「只关掉 Skill 检索」变得不可能。ES 客户端仍复用同一个 Bean，
 * 因此 {@code oj.search.enabled=false} 时这条链路也一起失效（没有连接可用）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oj.skill-search")
public class SkillSearchProperties {

    /** 总开关：false 时不建索引、审核通过时也不双写（检索接口 503） */
    private boolean enabled = true;

    /** 索引名。mapping 变更时要换名字 + 全量重建 */
    private String indexName = "learning_skill_v1";

    /** 在线检索缺省返回条数 */
    private int defaultTopK = 5;

    /** topK 上限：给工具层留出截断余量（工具最终只取 top-3） */
    private int maxTopK = 10;

    /** RRF：与题目检索同参数（契约值，不在评估集上调参） */
    private int rrfK = 60;
    private double rrfWeight = 1.0;

    /** hybrid 时每路取多少候选再融合 */
    private int hybridCandidates = 50;

    /** 索引里 content 的字符上限：MySQL 契约是 500 字，多出来的只会在重建时丢 */
    private int contentMaxChars = 500;

    /** 单次 bulk 的文档数 */
    private int reindexBatchSize = 100;
}
