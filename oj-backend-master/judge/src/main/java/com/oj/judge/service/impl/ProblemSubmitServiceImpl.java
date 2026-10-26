package com.oj.judge.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.common.redis.constant.RedisConstant;
import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.counter.ProblemSubmitCounter;
import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.mapper.ProblemSubmitMapper;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.memory.event.LearningEventRecorder;
import com.oj.judge.memory.event.LearningEventType;
import com.oj.judge.model.dto.problem.JudgeCase;
import com.oj.judge.model.dto.problemsubmit.ProblemSubmitAddReq;
import com.oj.judge.model.dto.problemsubmit.ProblemSubmitQueryReq;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.LanguageEnum;
import com.oj.judge.model.enums.ProblemDifficultyEnum;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;
import com.oj.judge.model.vo.problemsubmit.PassCountVo;
import com.oj.judge.model.vo.problemsubmit.ProblemSubmitVo;
import com.oj.judge.model.vo.problemsubmit.SubmitStatusVo;
import com.oj.judge.model.vo.problemsubmit.SubmitSummaryVo;
import com.oj.judge.mq.JudgeMessagePublisher;
import com.oj.judge.mq.JudgeMqConstants;
import com.oj.judge.mq.JudgeTaskMessage;
import com.oj.judge.service.JudgeService;
import com.oj.judge.service.ProblemSubmitService;
import com.oj.judge.statemachine.JudgeState;
import com.oj.judge.statemachine.JudgeStateService;
import com.oj.user.api.dto.SecretDTO;
import com.oj.user.api.service.UserInnerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author OJ
 * @description 针对表【question_submit(题目提交)】的数据库操作Service实现
 * @createDate 2023-08-24 10:36:35
 */
@Slf4j
@Service
public class ProblemSubmitServiceImpl extends ServiceImpl<ProblemSubmitMapper, ProblemSubmit>
        implements ProblemSubmitService {

    @Resource
    private ProblemMapper problemMapper;
    @Resource
    private JudgeService judgeService;
    @Resource
    private Snowflake snowflake;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JudgeAsyncProperties judgeAsyncProperties;
    @Resource
    private JudgeStateService judgeStateService;
    @Resource
    private JudgeMessagePublisher judgeMessagePublisher;
    @Resource
    private ProblemSubmitCounter submitCounter;
    @Resource
    private JudgeMetrics metrics;
    @Resource
    private LearningEventRecorder memoryRecorder;
    @DubboReference
    private UserInnerService userInnerService;

    @Override
    public ProblemSubmitVo doProblemSubmit(ProblemSubmitAddReq problemSubmitAddReq) {
        // 1、校验
        String language = problemSubmitAddReq.getLanguage();
        LanguageEnum languageEnum = LanguageEnum.getEnumByValue(language);
        ThrowUtils.throwIf(languageEnum == null, new BusinessException(HttpCodeEnum.BAD_REQUEST, "不支持的编程语言"));

        String code = problemSubmitAddReq.getCode();
        ThrowUtils.throwIf(code == null || code.isBlank(),
                new BusinessException(HttpCodeEnum.BAD_REQUEST, "代码不能为空"));
        int codeBytes = code.getBytes(StandardCharsets.UTF_8).length;
        ThrowUtils.throwIf(codeBytes > judgeAsyncProperties.getAsync().getMaxCodeBytes(),
                new BusinessException(HttpCodeEnum.BAD_REQUEST, "代码过长，请控制在 60KB 以内"));

        long problemId = problemSubmitAddReq.getProblemId();
        long stageStart = nanoNow();
        // 只取提交路径需要的列：content/answer 是大字段，这里用不到
        Problem problem = problemMapper.selectOne(new QueryWrapper<Problem>()
                .select("id", "judge_case", "is_delete")
                .eq("id", problemId));
        // is_delete 必须校验：软删题目不能再被提交（P1 之前的行为，P2 改成列裁剪时漏了）
        ThrowUtils.throwIf(problem == null || isDeleted(problem), new BusinessException(HttpCodeEnum.NOT_EXIST, "题目不存在"));
        int total = countJudgeCases(problem.getJudgeCase());
        long loadMs = elapsedMs(stageStart);

        // 2、题目提交数 +1 改为写后置：请求线程只做内存累加，定时任务合并成一条 UPDATE。
        // 原实现每次提交都对 problem 同一行做 UPDATE，120 并发下所有请求在那把行锁上排队（P2 归因）。
        submitCounter.increment(problemId);

        // 3、落库 WAITING
        Long userId = TokenUtils.getCurrentUid();
        Date now = new Date();
        ProblemSubmit problemSubmit = new ProblemSubmit();
        problemSubmit.setId(snowflake.nextId());
        problemSubmit.setUserId(userId);
        problemSubmit.setProblemId(problemId);
        problemSubmit.setCode(code);
        problemSubmit.setLanguage(language);
        problemSubmit.setCreateTime(now);
        problemSubmit.setStatus(ProblemSubmitStatusEnum.WAITING.getValue());
        problemSubmit.setJudgeInfo(JSONUtil.toJsonStr(JudgeInfo.pending(total, "排队中")));
        problemSubmit.setVersion(0);
        problemSubmit.setAttempt(0);
        problemSubmit.setEnqueuedAt(now);
        long insertStart = nanoNow();
        boolean save = save(problemSubmit);
        long insertMs = elapsedMs(insertStart);
        ThrowUtils.throwIf(!save, HttpCodeEnum.INTERNAL_SERVER_ERROR, "提交失败");

        // 埋点：同步链路也记，保证 mode=sync 回滚时学习事件不断档（P4 契约 §2.1）
        recordSubmit(problemSubmit, codeBytes, total);

        // 4、同步回滚链路
        if (!judgeAsyncProperties.isAsyncMode()) {
            return doSyncJudge(problemSubmit, userId);
        }

        // 5、异步链路：初始化 Redis 状态 + 投递消息
        long redisStart = nanoNow();
        judgeStateService.initIfAbsent(problemSubmit.getId(), userId, problemId, problemSubmit.getJudgeInfo());
        long redisMs = elapsedMs(redisStart);
        JudgeTaskMessage task = buildTask(problemSubmit);
        long mqStart = nanoNow();
        // 只投到本地有界发布队列，不在请求线程里等 broker confirm（confirm 要等持久化落盘）
        boolean accepted = judgeMessagePublisher.publishAsync(task);
        long mqMs = elapsedMs(mqStart);
        if (!accepted) {
            // 本地队列满：行保持 WAITING，Redis 状态保留，由对账任务重投；不返回 500 让客户端重复提交
            log.error("evt=judge.submit.publish_deferred submitId={} traceId={} action=reconcile",
                    problemSubmit.getId(), task.getTraceId());
        }
        log.info("evt=submit.stage submitId={} traceId={} uid={} language={} codeBytes={} total={} priority={}"
                        + " loadMs={} insertMs={} redisMs={} mqMs={} deferred={} totalMs={}",
                problemSubmit.getId(), task.getTraceId(), userId, language, codeBytes, total, task.getPriority(),
                loadMs, insertMs, redisMs, mqMs, !accepted, elapsedMs(stageStart));
        metrics.count("submit.total");
        if (!accepted) {
            metrics.count("submit.publish_deferred");
        }
        metrics.recordStage("submit.load", loadMs);
        metrics.recordStage("submit.insert", insertMs);
        metrics.recordStage("submit.redis", redisMs);
        metrics.recordStage("submit.mq", mqMs);
        metrics.recordStage("submit.total", elapsedMs(stageStart));
        return ProblemSubmitVo.objToVo(problemSubmit);
    }

    private static long nanoNow() {
        return System.nanoTime();
    }

    private void recordSubmit(ProblemSubmit submit, int codeBytes, int total) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", submit.getLanguage());
        payload.put("codeBytes", codeBytes);
        payload.put("totalCases", total);
        memoryRecorder.recordForSubmit(LearningEventType.SUBMIT, submit.getUserId(), submit.getProblemId(),
                submit.getId(), payload);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 回滚开关 oj.judge.mode=sync 时走原同步链路。
     */
    private ProblemSubmitVo doSyncJudge(ProblemSubmit problemSubmit, Long userId) {
        SecretDTO secretDTO = userInnerService.getSecretByUid(userId);
        ProblemSubmitVo submitResult = judgeService.doJudge(problemSubmit, secretDTO.getSecretId(),
                secretDTO.getSecretKey());
        if (submitResult.getStatus().equals(ProblemSubmitStatusEnum.SUCCEED.getValue())) {
            checkInAsync(userId, LocalDate.now());
        }
        log.info("evt=judge.submit submitId={} uid={} mode=sync status={}",
                problemSubmit.getId(), userId, submitResult.getStatus());
        return submitResult;
    }

    private JudgeTaskMessage buildTask(ProblemSubmit submit) {
        return buildTask(submit, JudgeMqConstants.PRIORITY_NORMAL, 1);
    }

    @Override
    public JudgeTaskMessage buildTask(ProblemSubmit submit, String priority, int attempt) {
        long now = System.currentTimeMillis();
        long enqueuedAt = submit.getEnqueuedAt() == null ? now : submit.getEnqueuedAt().getTime();
        return JudgeTaskMessage.builder()
                .schemaVersion(JudgeTaskMessage.SCHEMA_VERSION)
                .msgId(UUID.randomUUID().toString())
                .submitId(submit.getId())
                .problemId(submit.getProblemId())
                .uid(submit.getUserId())
                .language(submit.getLanguage())
                .attempt(Math.max(attempt, 1))
                .priority(JudgeMqConstants.normalizePriority(priority))
                .enqueuedAt(enqueuedAt)
                .traceId(UUID.randomUUID().toString().replace("-", ""))
                .code(null)
                .build();
    }

    private int countJudgeCases(String judgeCaseJson) {
        if (judgeCaseJson == null || judgeCaseJson.isBlank()) {
            return 0;
        }
        try {
            return JSONUtil.toList(judgeCaseJson, JudgeCase.class).size();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public ProblemSubmitVo getSubmitVoById(Long id) {
        ProblemSubmit submit = getById(id);
        ThrowUtils.throwIf(submit == null || isDeleted(submit), HttpCodeEnum.NOT_EXIST, "提交不存在");
        Long uid = TokenUtils.getCurrentUid();
        ThrowUtils.throwIf(!submit.getUserId().equals(uid), HttpCodeEnum.NO_AUTH, "没有查看该提交的权限");

        ProblemSubmitVo vo = ProblemSubmitVo.objToVo(submit);
        JudgeState state = judgeStateService.getState(id);
        if (state != null) {
            vo.setStatus(state.getStatus());
            vo.setJudgeInfo(SubmitStatusVo.parseJudgeInfo(state.getJudgeInfo()));
        }
        return vo;
    }

    @Override
    public SubmitStatusVo getSubmitStatus(Long id) {
        Long uid = TokenUtils.getCurrentUid();
        JudgeState state = judgeStateService.getState(id);
        if (state != null) {
            ThrowUtils.throwIf(state.getUid() != uid, HttpCodeEnum.NO_AUTH, "没有查看该提交的权限");
            SubmitStatusVo vo = new SubmitStatusVo();
            vo.setId(id);
            vo.setStatus(state.getStatus());
            vo.setJudgeInfo(SubmitStatusVo.parseJudgeInfo(state.getJudgeInfo()));
            vo.setVersion(state.getVersion());
            vo.setUpdateTime(new Date(state.getUpdatedAt()));
            return vo;
        }
        ProblemSubmit submit = getById(id);
        ThrowUtils.throwIf(submit == null || isDeleted(submit), HttpCodeEnum.NOT_EXIST, "提交不存在");
        ThrowUtils.throwIf(!submit.getUserId().equals(uid), HttpCodeEnum.NO_AUTH, "没有查看该提交的权限");
        judgeStateService.cacheFromDb(submit);
        return SubmitStatusVo.of(submit);
    }

    @Override
    public Page<ProblemSubmitVo> listProblemSubmitVoByPage(ProblemSubmitQueryReq problemSubmitQueryReq) {
        long pageNum = problemSubmitQueryReq.getCurrent();
        long pageSize = problemSubmitQueryReq.getSize();
        Long problemId = problemSubmitQueryReq.getProblemId();
        Long uid = TokenUtils.getCurrentUid();
        Page<ProblemSubmit> page = page(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ProblemSubmit>()
                        .eq(ProblemSubmit::getUserId, uid)
                        .eq(ProblemSubmit::getProblemId, problemId)
                        .orderByDesc(ProblemSubmit::getCreateTime));

        Page<ProblemSubmitVo> voPage = new Page<>(pageNum, pageSize, page.getTotal());
        List<ProblemSubmitVo> records = page.getRecords().stream().map(ProblemSubmitVo::objToVo)
                .collect(Collectors.toList());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public SubmitSummaryVo getSubmitSummary(Long uid) {
        SubmitSummaryVo summaryVo = new SubmitSummaryVo();

        // 获取用户通过的简单、中等、困难题目数
        PassCountVo easyPass = baseMapper.getPassCount(uid, ProblemDifficultyEnum.EASY.getValue());
        PassCountVo mediumPass = baseMapper.getPassCount(uid, ProblemDifficultyEnum.MEDIUM.getValue());
        PassCountVo hardPass = baseMapper.getPassCount(uid, ProblemDifficultyEnum.HARD.getValue());
        summaryVo.setEasyPass(easyPass.getPassCount());
        summaryVo.setEasyTotal(easyPass.getTotalCount());
        summaryVo.setMediumPass(mediumPass.getPassCount());
        summaryVo.setMediumTotal(mediumPass.getTotalCount());
        summaryVo.setHardPass(hardPass.getPassCount());
        summaryVo.setHardTotal(hardPass.getTotalCount());

        // 获取用户提交总数
        Integer submitCount = baseMapper.selectCount(new LambdaQueryWrapper<ProblemSubmit>()
                .eq(ProblemSubmit::getUserId, uid)).intValue();
        summaryVo.setSubmitCount(submitCount);
        // 获取用户成功的提交
        Integer passCount = baseMapper.selectCount(new LambdaQueryWrapper<ProblemSubmit>()
                .eq(ProblemSubmit::getUserId, uid)
                .eq(ProblemSubmit::getStatus, ProblemSubmitStatusEnum.SUCCEED.getValue())).intValue();
        summaryVo.setPassCount(passCount);

        return summaryVo;
    }

    @Override
    public List<String> getCheckInDatesByMysql(Long uid, String date) {
        YearMonth yearMonth = YearMonth.parse(date, DateTimeFormatter.ofPattern("yyyy-MM"));
        LocalDate startDate = yearMonth.atDay(1); // 月初
        LocalDate endDate = yearMonth.plusMonths(1).atDay(1); // 下月月初

        // 如果需要字符串格式
        String startDateStr = startDate.toString(); // "2025-12-01"
        String endDateStr = endDate.toString(); // "2026-01-01"

        List<String> checkInDates = baseMapper.getCheckInDates(uid, startDateStr, endDateStr);
        return checkInDates;
    }

    @Override
    public List<String> getCheckInDatesByRedis(Long uid, String date) {
        YearMonth yearMonth = YearMonth.parse(date, DateTimeFormatter.ofPattern("yyyy-MM"));
        int daysInMonth = yearMonth.lengthOfMonth();
        String key = String.format(RedisConstant.CHECK_IN_FORMAT, uid, yearMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        byte[] rawKey = stringRedisTemplate.getStringSerializer().serialize(key);
        byte[] bitmap = stringRedisTemplate.execute((RedisCallback<byte[]>)connection -> connection.get(rawKey));
        if (bitmap == null) {
            return new ArrayList<>();
        }
        List<String> checkInDates = new ArrayList<>();
        for (int i = 0; i < daysInMonth; i++) {
            int byteIndex = i / 8;
            if (byteIndex >= bitmap.length) {
                break;
            }
            int bitIndex = i % 8;
            boolean signed = (bitmap[byteIndex] & (1 << (7 - bitIndex))) != 0;
            if (signed) {
                LocalDate signDate = yearMonth.atDay(i + 1);
                checkInDates.add(signDate.toString());
            }
        }
        return checkInDates;
    }

    @Override
    @Async
    public Future<Boolean> checkInAsync(Long uid, LocalDate date) {
        String key = String.format("checkin:%d:%s", uid, date.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int offset = date.getDayOfMonth() - 1;
        stringRedisTemplate.opsForValue().setBit(key, offset, true);
        return new AsyncResult<>(true);
    }

    private boolean isDeleted(ProblemSubmit submit) {
        return submit.getIsDelete() != null && submit.getIsDelete() == 1;
    }

    private boolean isDeleted(Problem problem) {
        return problem.getIsDelete() != null && problem.getIsDelete() == 1;
    }

    @Override
    public int applyClaim(Long id, int version, String workerId, Integer attempt, long epoch,
            Date startedAt) {
        return baseMapper.applyClaim(id, ProblemSubmitStatusEnum.RUNNING.getValue(), version, workerId,
                attempt, epoch, startedAt);
    }

    @Override
    public int applyReset(Long id, int version, Integer attempt) {
        return baseMapper.applyReset(id, ProblemSubmitStatusEnum.WAITING.getValue(), version, attempt);
    }

    @Override
    public int applyTransitionFenced(Long id, int status, int version, String workerId, Integer attempt,
            long epoch, String judgeInfo, Date startedAt, Date finishedAt) {
        return baseMapper.applyTransitionFenced(id, status, version, workerId, attempt, epoch, judgeInfo,
                startedAt, finishedAt);
    }

    @Override
    public void onJudgeAccepted(Long problemId, Long uid) {
        problemMapper.update(null, new UpdateWrapper<Problem>()
                .setSql("accepted_num = accepted_num + 1").eq("id", problemId));
        checkInAsync(uid, LocalDate.now());
    }

    /**
     * 重新判题：高优来源之一（另一个是等待老化）。
     *
     * <p>顺序是「先重置 MySQL，再删 Redis 快照」：如果先删 Redis，在删完到落库之间若有
     * 轮询请求（{@link JudgeStateService#cacheFromDb}）就可能把旧终态回填进 Redis，
     * Worker 随后把该任务当 terminal 直接 skip，且对账不会清 Redis，任务会永远不再判。
     * 反过来先落库后删，任何回填读到的都是 WAITING。
     */
    @Override
    public ProblemSubmitVo rejudge(Long id) {
        ThrowUtils.throwIf(!judgeAsyncProperties.isAsyncMode(), HttpCodeEnum.BAD_REQUEST, "同步模式不支持重新判题");
        ProblemSubmit submit = getById(id);
        ThrowUtils.throwIf(submit == null || isDeleted(submit), HttpCodeEnum.NOT_EXIST, "提交不存在");
        Long uid = TokenUtils.getCurrentUid();
        ThrowUtils.throwIf(!submit.getUserId().equals(uid), HttpCodeEnum.NO_AUTH, "没有重新判题的权限");
        ThrowUtils.throwIf(submit.getStatus() != null
                        && submit.getStatus() == ProblemSubmitStatusEnum.RUNNING.getValue(),
                HttpCodeEnum.BAD_REQUEST, "判题中，请稍后再试");

        int attempt = (submit.getAttempt() == null ? 0 : submit.getAttempt()) + 1;
        int version = (submit.getVersion() == null ? 0 : submit.getVersion()) + 1;
        Date now = new Date();
        // priority / enqueued_at 必须一起落库：否则老化任务会把正在判题的高优行当成积压候选
        int rows = baseMapper.applyRejudge(id, ProblemSubmitStatusEnum.WAITING.getValue(), version, attempt,
                JudgeMqConstants.PRIORITY_HIGH, now);
        ThrowUtils.throwIf(rows == 0, HttpCodeEnum.INTERNAL_SERVER_ERROR, "重新判题失败");
        judgeStateService.delete(id);

        submit.setStatus(ProblemSubmitStatusEnum.WAITING.getValue());
        submit.setVersion(version);
        submit.setAttempt(attempt);
        submit.setEnqueuedAt(now);
        submit.setJudgeInfo(JSONUtil.toJsonStr(JudgeInfo.pending(0, "排队中")));
        submit.setPriority(JudgeMqConstants.PRIORITY_HIGH);
        JudgeTaskMessage task = buildTask(submit, JudgeMqConstants.PRIORITY_HIGH, attempt);
        boolean accepted = judgeMessagePublisher.publishAsync(task);
        metrics.count("submit.rejudge");
        if (!accepted) {
            metrics.count("submit.publish_deferred");
        }
        log.info("evt=submit.rejudge submitId={} traceId={} uid={} attempt={} version={} priority={} deferred={}",
                id, task.getTraceId(), uid, attempt, version, task.getPriority(), !accepted);
        return ProblemSubmitVo.objToVo(submit);
    }

    @Override
    public boolean promoteAging(ProblemSubmit row, String from, String to) {
        // CAS：只有「仍在排队 + 优先级没被人改过」的行才晋升，避免重复晋升/覆盖已消费行
        if (baseMapper.updatePriority(row.getId(), from, to) != 1) {
            return false;
        }
        int attempt = row.getAttempt() == null ? 1 : Math.max(row.getAttempt(), 1);
        JudgeTaskMessage task = buildTask(row, to, attempt);
        boolean published = judgeMessagePublisher.publishAsync(task);
        long waitedMs = row.getEnqueuedAt() == null ? -1L : System.currentTimeMillis() - row.getEnqueuedAt().getTime();
        metrics.count("aging.promoted." + to.toLowerCase());
        if (!published) {
            // 不能静默：本地发布队列满说明重投被丢了，只能等对账按 WAITING 重新捡起
            metrics.count("aging.publish_failed");
        }
        log.info("evt=judge.aging submitId={} traceId={} from={} to={} waitedMs={} published={}",
                row.getId(), task.getTraceId(), from, to, waitedMs, published);
        return true;
    }
}
