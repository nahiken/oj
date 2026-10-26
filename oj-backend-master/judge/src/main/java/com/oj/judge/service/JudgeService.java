package com.oj.judge.service;

import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;
import com.oj.judge.model.vo.problemsubmit.ProblemSubmitVo;

/**
 * @author OJ
 * @date 2023/8/26 15:31
 * @description 判题服务，抽象出微服务
 */
public interface JudgeService {

    /**
     * 同步判题：写 RUNNING、执行、写结果、维护题目通过数。仅回滚链路使用。
     */
    ProblemSubmitVo doJudge(ProblemSubmit problemSubmit, String secretId, String secretKey);

    /**
     * 只执行判题，不写任何状态。异步 Worker 用它在 CAS 保护下自行落状态。
     */
    JudgeInfo executeJudge(ProblemSubmit problemSubmit, String secretId, String secretKey);

    /**
     * 同上，但把 traceId 带进沙箱阶段日志（结构化日志要求 evt/submitId/traceId 齐全）。
     */
    JudgeInfo executeJudge(ProblemSubmit problemSubmit, String secretId, String secretKey, String traceId);
}
