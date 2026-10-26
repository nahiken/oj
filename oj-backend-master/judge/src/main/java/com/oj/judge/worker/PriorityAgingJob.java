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
import com.oj.judge.mq.JudgeMqConstants;
import com.oj.judge.service.ProblemSubmitService;

import lombok.extern.slf4j.Slf4j;

/**
 * 等待老化：排队超过阈值的任务提升优先级，避免低优任务被高优洪峰饿死。
 *
 * <p>规则（契约 0002 §4.3，阈值见 {@code oj.judge.async.aging-*}）：等待超过
 * {@code aging-promote-after-ms} 升一档（RUN→NORMAL、NORMAL→HIGH），超过
 * {@code aging-force-high-after-ms} 直接 HIGH。阈值不能设得太小：正常排队也会命中，
 * 会重复晋升并在洪峰时放大消息量（P2 实测 5s/15s 时 40 条任务有 24 条被重复晋升）。
 * 用 {@code problem_submit.priority} 做 CAS，只有真正改成功的行才重投。
 *
 * <p>本类只做「扫描 + 算目标档位」；CAS 改库与重投都交给
 * {@link ProblemSubmitService#promoteAging}，保证状态写入只经 Service（ARCHITECTURE §横切接口）。
 *
 * <p>重投是幂等的：Worker 先看状态机，已终态/已认领会跳过。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.judge", name = "mode", havingValue = "async", matchIfMissing = true)
public class PriorityAgingJob {

    @Resource
    private ProblemSubmitMapper problemSubmitMapper;
    @Resource
    private ProblemSubmitService problemSubmitService;
    @Resource
    private JudgeAsyncProperties properties;

    @Scheduled(initialDelayString = "${oj.judge.async.aging-delay-ms:2000}",
            fixedDelayString = "${oj.judge.async.aging-delay-ms:2000}")
    public void promote() {
        JudgeAsyncProperties.AsyncProps async = properties.getAsync();
        if (!async.isAgingEnabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Date before = new Date(now - async.getAgingPromoteAfterMs());
            List<ProblemSubmit> rows = problemSubmitMapper.selectAgingCandidates(before, async.getAgingBatch());
            int promoted = 0;
            for (ProblemSubmit row : rows) {
                String current = JudgeMqConstants.normalizePriority(row.getPriority());
                long waitedMs = row.getEnqueuedAt() == null ? 0L : now - row.getEnqueuedAt().getTime();
                String target = waitedMs >= async.getAgingForceHighAfterMs() ? JudgeMqConstants.PRIORITY_HIGH
                        : JudgeMqConstants.nextPriority(current);
                if (target.equals(current)) {
                    continue;
                }
                // CAS 改 priority + 重投都在 Service 里（状态写入只经 ProblemSubmitService）
                if (problemSubmitService.promoteAging(row, current, target)) {
                    promoted++;
                }
            }
            if (promoted > 0) {
                log.info("evt=judge.aging.batch promoted={} candidates={}", promoted, rows.size());
            }
        } catch (Exception e) {
            log.error("evt=judge.aging.error", e);
        }
    }
}
