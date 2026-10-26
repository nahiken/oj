package com.oj.codesandbox.model.dto;

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
     *
     * <p>judge 侧原样记日志，不重复打点。P2 契约见 docs/design-docs/0002-judge-pool.md §3.4。
     */
    private Map<String, Long> stageMs;
}
