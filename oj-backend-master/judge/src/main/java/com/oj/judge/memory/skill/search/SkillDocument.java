package com.oj.judge.memory.skill.search;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 索引里的一条 Skill（spec D20 的字段白名单）。
 *
 * <p>没有 `status`：索引里**只有 ACTIVE 行**，状态是本索引的不变式而不是可过滤字段。
 * 没有 `ownerUid` 之外的任何用户数据：`uid` 只用来做「自己的个人 Skill」这一条可见性判断。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillDocument {

    private Long skillId;
    private String name;
    private String content;
    private List<String> tags = new ArrayList<>();
    /** PERSONAL | PLATFORM */
    private String scope;
    /** 个人 Skill 的 owner；平台 Skill 为 null 且不写入索引文档 */
    private String uid;
    /** 384 维向量；边车不可用时为 null，BM25 一路仍然生效 */
    private List<Float> embedding;
}
