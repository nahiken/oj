package com.oj.codesandbox.controller;

import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.oj.codesandbox.metrics.SandboxMetrics;
import com.oj.codesandbox.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.pool.SandboxSlotPool;
import com.oj.codesandbox.service.SandboxService;

import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class SandboxController {
    @Resource
    private SandboxService sandboxService;
    @Resource
    private SandboxSlotPool sandboxSlotPool;
    @Resource
    private SandboxMetrics sandboxMetrics;

    @PostMapping("/execute")
    public ExecuteCodeRes execute(@RequestBody ExecuteCodeReq executeCodeRequest) {
        return sandboxService.execute(executeCodeRequest);
    }

    /**
     * 池与阶段指标：{@code /sandbox/metrics}（网关路径 {@code /api/sandbox/metrics}）。
     *
     * <p>契约：docs/design-docs/0002-judge-pool.md §3.4。
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return sandboxMetrics.snapshot(sandboxSlotPool.snapshot());
    }
}
