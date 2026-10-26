package com.oj.codesandbox.sdk.model.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRes {
    // 执行状态码
    private Integer code;

    // 执行信息
    private String msg;

    // 执行结果
    private List<ExecuteResult> results;

    /**
     * 阶段耗时（毫秒）：acquire / compile / run / cleanup / total。
     */
    private Map<String, Long> stageMs;
}
