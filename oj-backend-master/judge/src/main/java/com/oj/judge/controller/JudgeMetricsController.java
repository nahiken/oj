package com.oj.judge.controller;

import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.core.dto.R;
import com.oj.judge.service.JudgeMetricsService;

/**
 * 判题链路指标：{@code GET /judge/metrics}（网关路径 {@code /api/judge/metrics}）。
 *
 * <p>提交路径的阶段耗时（submit.*）、判题任务队列/执行耗时（judge.*）、
 * 异步发布队列（publish.*）、老化晋升（aging.*）都在这里。
 *
 * <p>契约：docs/design-docs/0002-judge-pool.md §2、§4。
 */
@RestController
@RequestMapping("/metrics")
public class JudgeMetricsController {

    @Resource
    private JudgeMetricsService judgeMetricsService;

    @GetMapping
    @TokenCheck
    public R<Map<String, Object>> metrics() {
        return R.ok(judgeMetricsService.snapshot());
    }
}
