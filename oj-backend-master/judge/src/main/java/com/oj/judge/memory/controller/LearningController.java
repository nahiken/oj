package com.oj.judge.memory.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oj.common.auth.annotation.RoleCheck;
import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.auth.constant.UserConstant;
import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.dto.R;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.judge.memory.context.LearningContextService;
import com.oj.judge.memory.event.LearningEventRecorder;
import com.oj.judge.memory.event.LearningEventReportReq;
import com.oj.judge.memory.metrics.LearningMemoryMetricsService;
import com.oj.judge.memory.summary.EpisodeSummaryService;
import com.oj.judge.memory.vo.LearningContextVo;

/**
 * 学习记忆接口（契约 0004 §2.2、§6、§7）。
 *
 * <p>网关路径：`/api/judge/learning/**`。
 * 埋点上报必须登录：未登录的行为无法归属到用户，也就不构成记忆。
 */
@RestController
@RequestMapping("/learning")
public class LearningController {

    @Resource
    private LearningEventRecorder recorder;
    @Resource
    private LearningContextService contextService;
    @Resource
    private LearningMemoryMetricsService metricsService;
    @Resource
    private EpisodeSummaryService summaryService;

    /**
     * 前端批量上报：只接受 `view_solution` / `copy_code` / `dwell`。
     *
     * @return 实际接收条数（白名单外的条目被静默丢弃并计数）
     */
    @PostMapping("/event")
    @TokenCheck
    public R<Integer> report(@RequestBody @NotNull @Valid LearningEventReportReq request) {
        Long uid = TokenUtils.getCurrentUid();
        ThrowUtils.throwIf(uid == null, HttpCodeEnum.NOT_LOGIN, "未登录");
        return R.ok(recorder.recordClient(uid, request.getEvents()));
    }

    /**
     * 个性化 Context：当前题目 + 最近 Episode + top Pattern。
     */
    @GetMapping("/context")
    @TokenCheck
    public R<LearningContextVo> context(@RequestParam("problemId") @Min(0) Long problemId) {
        Long uid = TokenUtils.getCurrentUid();
        ThrowUtils.throwIf(uid == null, HttpCodeEnum.NOT_LOGIN, "未登录");
        return R.ok(contextService.build(uid, problemId));
    }

    /**
     * 记忆链路指标：事件分布、未消费数、Episode 延迟、Pattern 数。
     */
    @GetMapping("/metrics")
    @TokenCheck
    public R<Map<String, Object>> metrics() {
        return R.ok(metricsService.snapshot());
    }

    /**
     * 批量补 LLM 摘要（ADR 0005）：追加 `source='LLM'` 的行，规则摘要不动。
     */
    @PostMapping("/summary/llm")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<Map<String, Object>> summarize(@RequestParam(value = "episodeId", required = false) Long episodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", summaryService.summarize(episodeId));
        result.put("total", summaryService.countLlm());
        result.put("promptVersion", EpisodeSummaryService.PROMPT_VERSION);
        return R.ok(result);
    }
}
