package com.oj.judge.memory.dto;

import lombok.Data;

/** 创建 Skill 的请求体（契约 0006 §2）。 */
@Data
public class SkillCreateReq {

    private String name;
    private String content;
    /** PERSONAL（默认）/ PLATFORM */
    private String scope;
    /** 适用标签，落库为 JSON 数组 */
    private java.util.List<String> tags;
}
