package com.oj.judge.memory.model;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

/**
 * learning_pattern 行（契约 0004 §5）。
 *
 * <p>每用户每类型只有一行，重算时 version+1（`ON DUPLICATE KEY UPDATE`）。
 */
@Data
public class LearningPatternDO {

    public static final String TYPE_ERROR_FOCUS = "ERROR_FOCUS";
    public static final String TYPE_WEAK_TAG = "WEAK_TAG";
    public static final String TYPE_ATTEMPT_STYLE = "ATTEMPT_STYLE";

    private Long id;
    private Long uid;
    private String patternType;
    private String description;
    /** JSON：支撑结论的计数，必须能被人读懂 */
    private String evidence;
    private BigDecimal confidence;
    private Integer version;
    private String embeddingRef;
    private Date createdAt;
    private Date updatedAt;
}
