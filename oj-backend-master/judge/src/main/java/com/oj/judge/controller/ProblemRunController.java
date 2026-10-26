package com.oj.judge.controller;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.core.dto.R;
import com.oj.judge.annotation.RateLimit;
import com.oj.judge.model.dto.problem.ProblemRunReq;
import com.oj.judge.service.ProblemRunService;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/run")
@Slf4j
public class ProblemRunController {
    @Resource
    private ProblemRunService problemRunService;

    /**
     * 在自定义的测试用例上运行代码
     * 
     * @param problemRunRequest
     * @param request
     * @return
     */
    @PostMapping
    @TokenCheck
    @RateLimit(key = "rate_limit:problem_run_submit", message = "提交过于频繁，请稍后再试")
    public R<ExecuteCodeRes> doProblemRun(@RequestBody @NotNull @Valid ProblemRunReq request) {
        ExecuteCodeRes response = problemRunService.doProblemRun(request);
        return R.ok(response);
    }
}
