package com.oj.judge.memory.skill.search;

import java.util.List;

import lombok.Data;

/**
 * `search_skill` 的一条命中。字段刻意少：工具层只允许 top-3 × 120 字，
 * 多给字段只会诱导模型复述正文。
 */
@Data
public class SkillSearchHit {

    private Long skillId;
    private String name;
    private String content;
    private List<String> tags;
    /** PERSONAL | PLATFORM，工具层据此说明「这是平台经验」还是「你自己的」 */
    private String scope;
    /** RRF 融合分 */
    private Double score;
    /** 两路名次，null 表示该路没召回它——可复核的证据 */
    private Integer bm25Rank;
    private Integer denseRank;
}
