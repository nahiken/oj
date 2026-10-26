package com.oj.judge.controller;

import java.util.List;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.dto.R;
import com.oj.judge.annotation.RateLimit;
import com.oj.judge.model.dto.problemsubmit.ProblemSubmitAddReq;
import com.oj.judge.model.dto.problemsubmit.ProblemSubmitQueryReq;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.vo.problemsubmit.ProblemSubmitVo;
import com.oj.judge.model.vo.problemsubmit.SubmitStatusVo;
import com.oj.judge.model.vo.problemsubmit.SubmitSummaryVo;
import com.oj.judge.service.ProblemSubmitService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.extern.slf4j.Slf4j;

/**
 * @author OJ
 * @date 2023/8/24 17:07
 * @description 题目提交接口
 */
@RestController
@RequestMapping("/submit")
@Slf4j
public class ProblemSubmitController {
    @Resource
    private ProblemSubmitService problemSubmitService;

    /**
     * 提交题目
     * 
     * @param problemSubmitAddRequest
     * @return 提交记录的 id
     */
    @PostMapping
    @TokenCheck
    @RateLimit(key = "rate_limit:problem_run_submit", message = "提交过于频繁，请稍后再试")
    public R<ProblemSubmitVo> doProblemSubmit(
            @RequestBody @NotNull @Valid ProblemSubmitAddReq problemSubmitAddRequest) {
        ProblemSubmitVo submitResult = problemSubmitService.doProblemSubmit(problemSubmitAddRequest);
        return R.ok(submitResult);
    }

    /**
     * 分页获取题目提交历史
     * 
     * @param problemSubmitQueryReq
     * @return
     */
    @PostMapping("/page/vo")
    @TokenCheck
    public R<Page<ProblemSubmitVo>> listProblemSubmitVoByPage(
            @RequestBody ProblemSubmitQueryReq problemSubmitQueryReq) {
        Page<ProblemSubmitVo> page = problemSubmitService.listProblemSubmitVoByPage(problemSubmitQueryReq);
        // 返回脱敏信息
        return R.ok(page);
    }

    /**
     * 获取某次历史提交的详细信息
     * 
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @TokenCheck
    public R<ProblemSubmitVo> getProblemSubmitVoById(@PathVariable("id") Long id, HttpServletRequest request) {
        return R.ok(problemSubmitService.getSubmitVoById(id));
    }

    /**
     * 轮询提交状态：不返回代码等大字段，供前端提交后轮询使用。
     *
     * <p>契约：docs/design-docs/0001-async-judge.md §9.2。
     */
    @GetMapping("/{id}/status")
    @TokenCheck
    public R<SubmitStatusVo> getSubmitStatus(@PathVariable("id") Long id) {
        return R.ok(problemSubmitService.getSubmitStatus(id));
    }

    /**
     * 重新判题：把提交重置为 WAITING 并以 HIGH 优先级重投（P2 高优来源）。
     *
     * <p>契约：docs/design-docs/0002-judge-pool.md §4.5。
     */
    @PostMapping("/{id}/rejudge")
    @TokenCheck
    public R<ProblemSubmitVo> rejudge(@PathVariable("id") Long id) {
        return R.ok(problemSubmitService.rejudge(id));
    }

    /**
     * 获取提交统计
     * 
     * @return
     */
    @GetMapping("/summary")
    @TokenCheck
    public R<SubmitSummaryVo> getSubmitSummary() {
        Long uid = TokenUtils.getCurrentUid();
        SubmitSummaryVo vo = problemSubmitService.getSubmitSummary(uid);
        return R.ok(vo);
    }

    /**
     * 获取用户某月的打卡日期列表
     * 
     * @param date 格式为 "yyyy-MM"
     * @return
     */
    @GetMapping("/checkIn/{date}")
    @TokenCheck
    public R<List<String>> getCheckInDates(@PathVariable("date") String date) {
        Long uid = TokenUtils.getCurrentUid();
        List<String> checkInDates = problemSubmitService.getCheckInDatesByRedis(uid, date);
        return R.ok(checkInDates);
    }

}
