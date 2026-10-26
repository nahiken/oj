package com.oj.judge.service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Future;

import com.oj.judge.model.dto.problemsubmit.ProblemSubmitAddReq;
import com.oj.judge.model.dto.problemsubmit.ProblemSubmitQueryReq;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.vo.problemsubmit.ProblemSubmitVo;
import com.oj.judge.model.vo.problemsubmit.SubmitStatusVo;
import com.oj.judge.model.vo.problemsubmit.SubmitSummaryVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author OJ
 * @description 针对表【question_submit(题目提交)】的数据库操作Service
 * @createDate 2023-08-24 10:36:35
 */
public interface ProblemSubmitService extends IService<ProblemSubmit> {

    ProblemSubmitVo doProblemSubmit(ProblemSubmitAddReq problemSubmitAddReq);

    Page<ProblemSubmitVo> listProblemSubmitVoByPage(ProblemSubmitQueryReq problemSubmitQueryReq);

    /**
     * 提交详情（含代码），优先读 Redis 的实时状态。
     */
    ProblemSubmitVo getSubmitVoById(Long id);

    /**
     * 轮询专用轻量状态。
     */
    SubmitStatusVo getSubmitStatus(Long id);

    SubmitSummaryVo getSubmitSummary(Long uid);

    List<String> getCheckInDatesByMysql(Long uid, String date);

    List<String> getCheckInDatesByRedis(Long uid, String date);

    Future<Boolean> checkInAsync(Long uid, LocalDate date);

    /**
     * 认领落库（不覆盖 judge_info）。返回 1 表示版本推进成功。
     *
     * @param epoch fencing token，认领时推进（契约 0003）
     */
    int applyClaim(Long id, int version, String workerId, Integer attempt, long epoch,
            java.util.Date startedAt);

    /**
     * 回退落库（重试/对账重置，不覆盖 judge_info）。
     */
    int applyReset(Long id, int version, Integer attempt);

    /**
     * 终态落库（单调版本 + fencing epoch）。这是唯一的终态写库入口：
     * 未带 epoch 的旧入口已删除，避免死信等路径绕过 fencing（复审 P2）。
     */
    int applyTransitionFenced(Long id, int status, int version, String workerId, Integer attempt,
            long epoch, String judgeInfo, java.util.Date startedAt, java.util.Date finishedAt);

    /**
     * 判题通过后的副作用：题目通过数 +1、用户签到。
     */
    void onJudgeAccepted(Long problemId, Long uid);

    /**
     * 重新判题：把终态提交重置为 WAITING 并以 HIGH 优先级重投（P2 高优来源）。
     */
    ProblemSubmitVo rejudge(Long id);

    /**
     * 老化晋升：CAS 把排队中的提交从 {@code from} 改到 {@code to}，改成功才重投。
     *
     * <p>状态写入与消息投递都在 Service 内完成（ARCHITECTURE §横切接口：Worker 只处理任务）。
     *
     * @return true 表示本次晋升生效（CAS 成功），false 表示行已被消费/已改档
     */
    boolean promoteAging(ProblemSubmit row, String from, String to);

    /**
     * 由提交行构造判题任务消息（attempt 默认规则只保留这一份实现）。
     *
     * <p>提交、老化、对账三条重投路径都走这里，避免各自的 attempt/enqueuedAt 语义分叉。
     */
    com.oj.judge.mq.JudgeTaskMessage buildTask(ProblemSubmit row, String priority, int attempt);
}
