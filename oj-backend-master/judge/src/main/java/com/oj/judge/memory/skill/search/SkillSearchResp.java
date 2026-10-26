package com.oj.judge.memory.skill.search;

import java.util.List;

import lombok.Data;

/** `GET /judge/search/skill` 的响应。 */
@Data
public class SkillSearchResp {

    private String query;
    private int topK;
    /** 两路融合后的候选总数（不是命中文档总数；ES 的 trackTotalHits 只在单路里有意义） */
    private int total;
    private List<SkillSearchHit> hits;
    private long esMs;
    private long embedMs;
    private long tookMs;
}
