package com.oj.judge.search.index;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 索引里的一道题。**字段白名单**（契约 0005 §2.2）：
 *
 * <p>没有 `answer`、没有 `judgeCase`、没有 `judgeConfig`——不写进文档，就不存在被检索出来的可能。
 * 新增字段前先确认它不是答案或测试用例。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProblemDocument {

    private Long problemId;
    private String title;
    private Content content;
    private List<String> tags;
    private String difficulty;
    private String lang;
    private String source;
    private Boolean isDelete;
    /** epoch millis，同时是 ES 外部版本号的来源（契约 0005 §3.2） */
    private Long updateTime;
    /** embedding 模型版本；P5a 只有 BM25，保持 null */
    private String modelId;
    /** 384 维向量；ticket 07 才写入 */
    private List<Float> embedding;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Content {
        private String en;
        private String zh;
    }
}
