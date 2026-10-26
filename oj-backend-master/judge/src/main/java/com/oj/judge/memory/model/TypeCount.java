package com.oj.judge.memory.model;

import lombok.Data;

/**
 * 通用「标签 + 计数」行：事件类型分布、失败状态分布都用它。
 *
 * <p>字段叫 `label` 而不是 `eventType`：`countWaStatus` 里装的是判题状态，
 * 沿用 `eventType` 会让读代码的人误判它的含义（复审 S6）。
 */
@Data
public class TypeCount {
    private String label;
    private Long total;
}
