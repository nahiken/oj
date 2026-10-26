package com.oj.judge.worker;

import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.mapper.ProblemSubmitMapper;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.mq.JudgeMessagePublisher;
import com.oj.judge.mq.JudgeMqConstants;
import com.oj.judge.mq.JudgeTaskMessage;
import com.oj.judge.service.ProblemSubmitService;
import com.oj.judge.statemachine.JudgeStateService;

import lombok.extern.slf4j.Slf4j;

/**
 * 对账任务：只扫 MySQL，修复「消息没投出去」与「消费者失联」两类卡单。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §8.3。
 * 重投是幂等的：Worker 会先看 Redis 终态，再 CAS 认领。
 * 写库统一走 {@link ProblemSubmitService}，本类只读查询与调度。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.judge", name = "mode", havingValue = "async", matchIfMissing = true)
public class JudgeReconcileJob {

    private static final int BATCH_LIMIT = 100;

    @Resource
    private ProblemSubmitMapper problemSubmitMapper;
    @Resource
    private ProblemSubmitService problemSubmitService;
    @Resource
    private JudgeStateService stateService;
    @Resource
    private JudgeMessagePublisher publisher;
    @Resource
    private JudgeAsyncProperties properties;

    @Scheduled(initialDelayString = "${oj.judge.async.reconcile-delay-ms:30000}",
            fixedDelayString = "${oj.judge.async.reconcile-delay-ms:30000}")
    public void reconcile() {
        try {
            republishStaleWaiting();
            resetStaleRunning();
        } catch (Exception e) {
            log.error("evt=judge.reconcile.error", e);
        }
    }

    private void republishStaleWaiting() {
        long before = System.currentTimeMillis() - properties.getAsync().getReconcileStaleWaitingMs();
        List<ProblemSubmit> rows = problemSubmitMapper.selectStaleWaiting(new Date(before), BATCH_LIMIT);
        int republished = 0;
        for (ProblemSubmit row : rows) {
            if (publisher.publish(buildTask(row, attemptOf(row)))) {
                republished++;
            }
        }
        if (!rows.isEmpty()) {
            log.warn("evt=judge.reconcile kind=stale_waiting found={} republished={}", rows.size(), republished);
        }
    }
    private void resetStaleRunning() {
        long before = System.currentTimeMillis() - properties.getAsync().getReconcileStaleRunningMs();
        List<ProblemSubmit> rows = problemSubmitMapper.selectStaleRunning(new Date(before), BATCH_LIMIT);
        int republished = 0;
        for (ProblemSubmit row : rows) {
            int nextAttempt = attemptOf(row) + 1;
            // P11：对账接管也要推进 epoch（契约 0003 §6）。不推进的话，重置到重新认领之间
            // 旧持有者仍能通过终态校验；expectedEpoch 用库里的值做守卫，避免覆盖别人刚认领的行。
            long nextEpoch = stateService.nextEpoch(row.getId());
            long dbEpoch = row.getEpoch() == null ? 0L : row.getEpoch();
            long result = stateService.transition(row.getId(), ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                    ProblemSubmitStatusEnum.WAITING.getValue(), null, nextAttempt, null, null, dbEpoch, nextEpoch);
            if (result != JudgeStateService.OK && result != JudgeStateService.NOT_FOUND) {
                continue;
            }
            int version = nextVersion(row);
            problemSubmitService.applyReset(row.getId(), version, nextAttempt);
            if (publisher.publish(buildTask(row, nextAttempt))) {
                republished++;
            }
        }
        if (!rows.isEmpty()) {
            log.warn("evt=judge.reconcile kind=stale_running found={} republished={}", rows.size(), republished);
        }
    }

    /**
     * 重投消息统一由 Service 构造（attempt 默认规则只有一份实现）。
     *
     * <p>契约 §4.4：重试保持原优先级，否则被对账捡回来的 HIGH 任务会降级成 NORMAL。
     */
    private JudgeTaskMessage buildTask(ProblemSubmit row, int attempt) {
        return problemSubmitService.buildTask(row,
                JudgeMqConstants.normalizePriority(row.getPriority()), attempt);
    }

    private int attemptOf(ProblemSubmit row) {
        return row.getAttempt() == null ? 1 : Math.max(row.getAttempt(), 1);
    }

    private int nextVersion(ProblemSubmit row) {
        return (row.getVersion() == null ? 0 : row.getVersion()) + 1;
    }
}
