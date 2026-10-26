package com.oj.judge.worker;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Resource;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;
import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.executor.WeightedJudgeDispatcher;
import com.oj.judge.lock.ClaimToken;
import com.oj.judge.lock.JudgeLease;
import com.oj.judge.lock.JudgeLeaseManager;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.memory.event.LearningEventRecorder;
import com.oj.judge.memory.event.LearningEventType;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.JudgeInfoEnum;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;
import com.oj.judge.mq.JudgeMessagePublisher;
import com.oj.judge.mq.JudgeMqConstants;
import com.oj.judge.mq.JudgeTaskMessage;
import com.oj.judge.service.JudgeService;
import com.oj.judge.service.ProblemSubmitService;
import com.oj.judge.statemachine.JudgeState;
import com.oj.judge.statemachine.JudgeStateService;
import com.oj.user.api.dto.SecretDTO;
import com.oj.user.api.service.UserInnerService;
import com.rabbitmq.client.Channel;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 判题 Worker：只负责 AMQP 语义（手动 ack、重投、死信）与流程编排；
 * 状态迁移的决策在 Redis（{@link JudgeStateService}），状态落库与 AC 副作用在 Service 层。
 *
 * <p>P2 起按优先级分三个监听容器（权重 4:2:1），执行交给隔离的有界线程池：
 * 监听线程阻塞等待任务完成再 ack，池满则按原优先级延迟重投形成背压。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §5、§8、§9；0002-judge-pool.md §4。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.judge", name = "mode", havingValue = "async", matchIfMissing = true)
public class JudgeWorker {

    @Resource
    private JudgeService judgeService;
    @Resource
    private ProblemSubmitService problemSubmitService;
    @Resource
    private JudgeStateService stateService;
    @Resource
    private JudgeMessagePublisher publisher;
    @Resource
    private JudgeAsyncProperties properties;
    @Resource
    private JudgeMetrics metrics;
    @Resource
    private WeightedJudgeDispatcher judgeDispatcher;
    @Resource
    private JudgeLeaseManager leaseManager;
    @Resource
    private LearningEventRecorder memoryRecorder;

    @DubboReference
    private UserInnerService userInnerService;

    private final String workerId = buildWorkerId();

    // ---------------- 三档监听 ----------------

    @RabbitListener(queues = JudgeMqConstants.QUEUE_HIGH, ackMode = "MANUAL",
            concurrency = "${oj.judge.async.high-concurrency:4}-${oj.judge.async.high-concurrency:4}")
    public void onHighMessage(JudgeTaskMessage task, Message message, Channel channel) throws IOException {
        dispatch(task, message, channel, JudgeMqConstants.PRIORITY_HIGH);
    }

    @RabbitListener(queues = JudgeMqConstants.QUEUE_NORMAL, ackMode = "MANUAL",
            concurrency = "${oj.judge.async.normal-concurrency:2}-${oj.judge.async.normal-concurrency:2}")
    public void onNormalMessage(JudgeTaskMessage task, Message message, Channel channel) throws IOException {
        dispatch(task, message, channel, JudgeMqConstants.PRIORITY_NORMAL);
    }

    @RabbitListener(queues = JudgeMqConstants.QUEUE_RUN, ackMode = "MANUAL",
            concurrency = "${oj.judge.async.run-concurrency:1}-${oj.judge.async.run-concurrency:1}")
    public void onRunMessage(JudgeTaskMessage task, Message message, Channel channel) throws IOException {
        dispatch(task, message, channel, JudgeMqConstants.PRIORITY_RUN);
    }

    /**
     * 把任务交给加权调度器并等待完成后再 ack：ack 语义仍然是「判完才确认」。
     */
    private void dispatch(JudgeTaskMessage task, Message message, Channel channel, String lane)
            throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        boolean redelivered = message.getMessageProperties().isRedelivered();
        long queueMs = task.getEnqueuedAt() == null ? -1L : System.currentTimeMillis() - task.getEnqueuedAt();
        try {
            Future<?> future = judgeDispatcher.submit(lane, () -> process(task, redelivered));
            future.get();
            channel.basicAck(deliveryTag, false);
            metrics.count("judge.lane." + lane.toLowerCase());
            if (queueMs >= 0) {
                metrics.recordStage("judge.queue", queueMs);
            }
        } catch (RejectedExecutionException e) {
            // 池队列满：不是丢任务，是背压——按原优先级延迟 1s 回队（TTL 最小档）
            metrics.count("judge.rejected");
            publisher.publishRetry(task, 1000L);
            channel.basicAck(deliveryTag, false);
            log.warn("evt=judge.task.rejected submitId={} traceId={} lane={} action=requeue_1s",
                    task.getSubmitId(), task.getTraceId(), lane);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.basicNack(deliveryTag, false, true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RetryableJudgeException) {
                RetryableJudgeException retry = (RetryableJudgeException) cause;
                // epoch = 异常发生时仍持有这一代租约；重试/置败迁移带它做 fencing（P15 方案 B）
                handleRetry(task, retry.getMessage(), channel, deliveryTag, retry.getEpoch());
                return;
            }
            // 没有认领身份（或无法证明属于哪一代）的异常不能做未 fencing 的状态重置：
            // 只把消息送进死信保留现场，由对账按 MySQL 状态兜底（复审 P15 方案 A）。
            log.error("evt=judge.task.error submitId={} traceId={} msgId={} action=dead_letter",
                    task.getSubmitId(), task.getTraceId(), task.getMsgId(), cause);
            metrics.count("judge.task.error");
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void process(JudgeTaskMessage task, boolean redelivered) {
        if (task.getSchemaVersion() == null || task.getSchemaVersion() != JudgeTaskMessage.SCHEMA_VERSION) {
            log.warn("evt=judge.task.unsupported submitId={} traceId={} schemaVersion={}",
                    task.getSubmitId(), task.getTraceId(), task.getSchemaVersion());
            return;
        }
        long submitId = task.getSubmitId();
        JudgeState state = stateService.getState(submitId);
        if (state == null) {
            state = rebuildState(submitId, task.getTraceId());
            if (state == null) {
                return;
            }
        }
        if (state.isTerminal()) {
            metrics.count("judge.skip");
            log.info("evt=judge.task.skip submitId={} traceId={} reason=terminal status={}",
                    submitId, task.getTraceId(), state.getStatus());
            return;
        }
        if (state.getStatus() == ProblemSubmitStatusEnum.RUNNING.getValue()) {
            handleRunningState(task, state, redelivered);
            return;
        }
        claimAndJudge(task, state);
    }

    /**
     * Redis 状态丢失时用 MySQL 重建；行已终态则直接返回终态快照。
     */
    private JudgeState rebuildState(long submitId, String traceId) {
        ProblemSubmit submit = problemSubmitService.getById(submitId);
        if (submit == null || isDeleted(submit)) {
            metrics.count("judge.skip");
            log.warn("evt=judge.task.skip submitId={} traceId={} reason=submit_missing", submitId, traceId);
            return null;
        }
        stateService.cacheFromDb(submit);
        JudgeState state = stateService.getState(submitId);
        if (state == null) {
            throw new RetryableJudgeException("状态重建失败");
        }
        return state;
    }

    /**
     * 已有 RUNNING：
     * <ul>
     *   <li>租约有效且消息未重投：别的 Worker 在跑，直接 ack</li>
     *   <li>租约有效但消息重投：不能证明持有者已死。<b>不接管也不丢弃</b>，
     *       按「租约剩余 + 2s」延迟回队，到期后走租约过期分支</li>
     *   <li>租约已过期：视为失联，释放旧锁、epoch+1，回退 WAITING 后重投</li>
     * </ul>
     */
    private void handleRunningState(JudgeTaskMessage task, JudgeState state, boolean redelivered) {
        long submitId = task.getSubmitId();
        long now = System.currentTimeMillis();
        boolean leaseAlive = state.getLeaseUntil() > now;
        if (leaseAlive) {
            if (!redelivered) {
                metrics.count("judge.skip");
                log.info("evt=judge.task.skip submitId={} traceId={} reason=running worker={}",
                        submitId, task.getTraceId(), state.getWorkerId());
                return;
            }
            long waitMs = Math.max(state.getLeaseUntil() - now + 2000L, 5000L);
            publisher.publishRetry(task, waitMs);
            metrics.count("judge.requeue_lease_alive");
            log.warn("evt=judge.task.skip submitId={} traceId={} reason=lease_alive_requeued delayMs={}",
                    submitId, task.getTraceId(), waitMs);
            return;
        }

        int nextAttempt = nextAttempt(task);
        // 先推进 epoch：从这一步起旧持有者的终态写入就会被 fence，
        // 不必等到「重新认领」才生效（否则重置完成到重新认领之间仍有覆盖窗口）
        long nextEpoch = stateService.nextEpoch(submitId);
        // P9：必须拿「我们读到的这一代」做 expectedEpoch。传 null 时，别人刚接管并认领
        // （epoch 已推进）的活任务会被这个过期 RUNNING 快照重置回 WAITING，形成二次执行。
        // 若 epoch 已变，Lua 返回 3，这里直接退出，由已接管者继续。
        long result = transitionWithRecovery(task, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, nextAttempt, null, null,
                (long) state.getEpoch(), nextEpoch);
        if (result == JudgeStateService.FENCED) {
            onFenced(task, (long) state.getEpoch(), "drop_takeover", ProblemSubmitStatusEnum.WAITING.getValue());
            return;
        }
        if (result != JudgeStateService.OK) {
            return;
        }
        // 旧持有者已被判定失联，释放它的锁；Lua 校验 token（workerId#epoch），不会误删别人的锁
        if (state.getWorkerId() != null) {
            stateService.unlock(submitId, ClaimToken.of(state.getWorkerId(), state.getEpoch()).value());
        }
        JudgeState reset = stateService.getState(submitId);
        int version = reset == null ? state.getVersion() + 1 : reset.getVersion();
        int rows = problemSubmitService.applyReset(submitId, version, nextAttempt);
        republish(task, nextAttempt);
        metrics.count("judge.takeover");
        log.warn("evt=judge.task.takeover submitId={} traceId={} reason=lease_expired epoch={} attempt={} dbRows={}",
                submitId, task.getTraceId(), nextEpoch, nextAttempt, rows);
    }

    private void claimAndJudge(JudgeTaskMessage task, JudgeState state) {
        long submitId = task.getSubmitId();
        // 抢锁即推进 epoch：token = workerId#epoch，续租与终态写入都用它证明「我是这一代」
        JudgeLease lease = leaseManager.acquire(submitId, workerId);
        if (lease == null) {
            // 锁还在别的执行者手里（可能刚崩溃、TTL 未到）。不要 ack 丢弃，延迟重投等锁过期。
            publisher.publishRetry(task, 5000L);
            metrics.count("judge.requeue_locked");
            log.info("evt=judge.task.skip submitId={} traceId={} reason=locked_by_other requeue=5s",
                    submitId, task.getTraceId());
            return;
        }
        try {
            judgeClaimed(task, state, lease);
        } catch (RetryableJudgeException e) {
            throw e;
        } catch (Exception e) {
            // P15（方案 B）：认领之后抛出的任何异常都带上这一代 epoch，
            // 重试/置败迁移才能被 fencing 保护，而不是传 null 做无守卫的重置。
            throw new RetryableJudgeException("判题链路异常: " + e.getMessage(), e, lease.getEpoch());
        } finally {
            leaseManager.release(lease);
        }
    }

    private void judgeClaimed(JudgeTaskMessage task, JudgeState state, JudgeLease lease) {
        long submitId = task.getSubmitId();
        long leaseUntil = System.currentTimeMillis() + properties.getAsync().getLeaseTtlMs();
        long claim = transitionWithRecovery(task, ProblemSubmitStatusEnum.WAITING.getValue(),
                state.getVersion(), ProblemSubmitStatusEnum.RUNNING.getValue(), workerId,
                task.getAttempt(), null, leaseUntil, null, lease.getEpoch());
        if (claim != JudgeStateService.OK) {
            log.info("evt=judge.cas.conflict submitId={} traceId={} expected=0 actual={} code={}",
                    submitId, task.getTraceId(), state.getStatus(), claim);
            return;
        }
        // 认领成功后才登记续租：避免「还没成为持有者就续租」的无效续租与误判丢失
        leaseManager.watch(lease);
        JudgeState claimed = stateService.getState(submitId);
        int claimedVersion = claimed == null ? state.getVersion() + 1 : claimed.getVersion();
        Date startedAt = new Date();
        int rows = problemSubmitService.applyClaim(submitId, claimedVersion, workerId,
                task.getAttempt(), lease.getEpoch(), startedAt);
        log.info("evt=judge.task submitId={} traceId={} msgId={} attempt={} priority={} status=1 worker={} epoch={} cas=0 dbRows={}",
                submitId, task.getTraceId(), task.getMsgId(), task.getAttempt(),
                JudgeMqConstants.normalizePriority(task.getPriority()), workerId, lease.getEpoch(), rows);

        JudgeInfo info;
        try {
            ProblemSubmit submit = problemSubmitService.getById(submitId);
            if (submit == null || isDeleted(submit)) {
                throw new BusinessException(HttpCodeEnum.NOT_EXIST, "提交不存在");
            }
            SecretDTO secret = userInnerService.getSecretByUid(submit.getUserId());
            info = judgeService.executeJudge(submit, secret.getSecretId(), secret.getSecretKey(),
                    task.getTraceId());
        } catch (BusinessException e) {
            log.warn("evt=judge.task.business_fail submitId={} traceId={} msg={}",
                    submitId, task.getTraceId(), e.getMessage());
            info = JudgeInfo.pending(0, e.getMessage());
            info.setStatus(JudgeInfoEnum.RUNTIME_ERROR.getValue());
            finish(task, claimed, info, startedAt, false, lease);
            return;
        } catch (Exception e) {
            throw new RetryableJudgeException("判题执行异常: " + e.getMessage(), e, lease.getEpoch());
        }
        boolean accepted = JudgeInfoEnum.ACCEPTED.getValue().equals(info.getStatus());
        finish(task, claimed, info, startedAt, accepted, lease);
    }

    private void finish(JudgeTaskMessage task, JudgeState claimed, JudgeInfo info, Date startedAt,
            boolean accepted, JudgeLease lease) {
        long submitId = task.getSubmitId();
        if (lease.isLost()) {
            // 续租失败 = 已经不是持有者：交回 WAITING（若已被接管，CAS 会返回 FENCED）
            abandonLease(task, lease);
            return;
        }
        int target = accepted ? ProblemSubmitStatusEnum.SUCCEED.getValue()
                : ProblemSubmitStatusEnum.FAILED.getValue();
        String judgeInfoJson = JSONUtil.toJsonStr(info);
        // 终态写入带 expectedEpoch：被接管的旧执行者在这里得到 FENCED，绝不覆盖新结果
        long result = transitionWithRecovery(task, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                target, null, null, judgeInfoJson, null, lease.getEpoch(), null);
        if (result == JudgeStateService.FENCED) {
            onFenced(task, lease.getEpoch(), "drop_result", target);
            return;
        }
        if (result != JudgeStateService.OK) {
            log.warn("evt=judge.cas.conflict submitId={} traceId={} target={} code={}",
                    submitId, task.getTraceId(), target, result);
            return;
        }
        JudgeState done = stateService.getState(submitId);
        int doneVersion = done == null ? claimed.getVersion() + 1 : done.getVersion();
        int rows = problemSubmitService.applyTransitionFenced(submitId, target, doneVersion, workerId,
                task.getAttempt(), lease.getEpoch(), judgeInfoJson, startedAt, new Date());
        if (rows == 1) {
            recordJudgeResult(task, info, accepted, startedAt);
            if (accepted) {
                problemSubmitService.onJudgeAccepted(task.getProblemId(), task.getUid());
            }
        }
        if (rows == 0) {
            log.warn("evt=judge.db.skip submitId={} traceId={} version={} epoch={} rows=0",
                    submitId, task.getTraceId(), doneVersion, lease.getEpoch());
        }
        long e2e = task.getEnqueuedAt() == null ? -1 : System.currentTimeMillis() - task.getEnqueuedAt();
        long now = System.currentTimeMillis();
        // 契约 §8：接管/重试后的 attempt>1 任务，从重新认领到终态的耗时就是恢复时间的主体。
        // 用认领时写入状态哈希的 updatedAt 做起点，不额外存时间戳。
        if (claimed != null && claimed.getAttempt() > 1) {
            metrics.recordStage("judge.recover", now - claimed.getUpdatedAt());
        }
        if (startedAt != null) {
            metrics.recordStage("judge.exec", now - startedAt.getTime());
        }
        if (e2e >= 0) {
            metrics.recordStage("judge.e2e", e2e);
        }
        metrics.count(accepted ? "judge.accepted" : "judge.failed");
        log.info("evt=judge.task.done submitId={} traceId={} priority={} status={} judgeInfoStatus={} dbRows={} epoch={} e2eMs={}",
                submitId, task.getTraceId(), JudgeMqConstants.normalizePriority(task.getPriority()), target,
                info.getStatus(), rows, lease.getEpoch(), e2e);
    }

    /**
     * 终态写库成功后记一条 ac/wa 事件（P4 契约 §2.1）。
     *
     * <p>只在 `rows==1` 之后记：被 fence 的旧执行者、或版本没推进的重复写入都不该产生结果事件，
     * 否则 Episode 会把同一次执行算成两次尝试。
     */
    private void recordJudgeResult(JudgeTaskMessage task, JudgeInfo info, boolean accepted, Date startedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("judgeStatus", info.getStatus());
        payload.put("pass", info.getPass());
        payload.put("total", info.getTotal());
        payload.put("attempt", task.getAttempt());
        if (startedAt != null) {
            payload.put("execMs", System.currentTimeMillis() - startedAt.getTime());
        }
        memoryRecorder.recordForSubmit(accepted ? LearningEventType.AC : LearningEventType.WA,
                task.getUid(), task.getProblemId(), task.getSubmitId(), payload);
    }

    /**
     * 租约丢失：不写终态，尽量把任务交回 WAITING 并重投。
     *
     * <p>若已被接管（CAS 返回 FENCED），什么都不做——接管者已经重投了下一 attempt。
     * 契约：docs/design-docs/0003-lease-fencing.md §5.3。
     */
    private void abandonLease(JudgeTaskMessage task, JudgeLease lease) {
        long submitId = task.getSubmitId();
        int nextAttempt = nextAttempt(task);
        long result = resetToWaiting(task, lease.getEpoch(), nextAttempt);
        metrics.count("judge.lease.abandoned");
        if (result == JudgeStateService.FENCED) {
            onFenced(task, lease.getEpoch(), "drop_abandon", ProblemSubmitStatusEnum.WAITING.getValue());
            return;
        }
        if (result != JudgeStateService.OK) {
            log.warn("evt=judge.lease.abandoned submitId={} traceId={} epoch={} action=drop code={}",
                    submitId, task.getTraceId(), lease.getEpoch(), result);
            return;
        }
        try {
            republish(task, nextAttempt);
        } catch (RetryableJudgeException e) {
            // 投递失败就先留在 WAITING，由对账（60s）捡回
            log.warn("evt=judge.lease.abandoned submitId={} traceId={} republish=false",
                    submitId, task.getTraceId());
        }
        log.warn("evt=judge.lease.abandoned submitId={} traceId={} epoch={} action=requeue attempt={}",
                submitId, task.getTraceId(), lease.getEpoch(), nextAttempt);
    }

    private void handleRetry(JudgeTaskMessage task, String reason, Channel channel, long deliveryTag,
            Long epoch) throws IOException {
        long submitId = task.getSubmitId();
        int nextAttempt = nextAttempt(task);
        int maxAttempt = properties.getAsync().getMaxAttempt();

        if (nextAttempt > maxAttempt) {
            JudgeInfo info = JudgeInfo.pending(0, "判题服务异常，已重试 " + maxAttempt + " 次，请稍后重新提交");
            info.setStatus(JudgeInfoEnum.RUNTIME_ERROR.getValue());
            String json = JSONUtil.toJsonStr(info);
            Long effectiveEpoch = epoch;
            if (effectiveEpoch == null) {
                JudgeState current = stateService.getState(submitId);
                if (current != null) {
                    effectiveEpoch = (long) current.getEpoch();
                }
            }
            if (effectiveEpoch == null) {
                // P2：拿不到 epoch 就不能安全写库（没有 fencing 的写库路径），只记录死信，
                // 由对账按 MySQL 的 RUNNING/WAITING 状态兜底。
                metrics.count("judge.dead");
                log.error("evt=judge.task.dead submitId={} traceId={} attempt={} reason={} action=ack_no_epoch",
                        submitId, task.getTraceId(), nextAttempt, reason);
                channel.basicAck(deliveryTag, false);
                return;
            }
            long result = transitionWithRecovery(task, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                    ProblemSubmitStatusEnum.FAILED.getValue(), null, nextAttempt, json, null, effectiveEpoch, null);
            if (result == JudgeStateService.FENCED) {
                onFenced(task, effectiveEpoch, "drop_dead", ProblemSubmitStatusEnum.FAILED.getValue());
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (result == JudgeStateService.OK) {
                JudgeState failed = stateService.getState(submitId);
                int version = failed == null ? 0 : failed.getVersion();
                // P2：死信终态也必须带 epoch 落库，否则这里仍是一条未 fencing 的写库路径
                problemSubmitService.applyTransitionFenced(submitId,
                        ProblemSubmitStatusEnum.FAILED.getValue(), version, workerId, nextAttempt,
                        effectiveEpoch, json, null, new Date());
            }
            metrics.count("judge.dead");
            log.error("evt=judge.task.dead submitId={} traceId={} attempt={} reason={}",
                    submitId, task.getTraceId(), nextAttempt, reason);
            // requeue=false → 走队列的 DLX，消息留档到 judge.queue.dead
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        long result = resetToWaiting(task, epoch, nextAttempt);
        if (result == JudgeStateService.FENCED) {
            onFenced(task, epoch, "drop_retry", ProblemSubmitStatusEnum.WAITING.getValue());
            channel.basicAck(deliveryTag, false);
            return;
        }
        republish(task, nextAttempt);
        long delay = retryDelay(nextAttempt);
        metrics.count("judge.retry");
        log.warn("evt=judge.task.retry submitId={} traceId={} attempt={} delayMs={} reason={}",
                submitId, task.getTraceId(), nextAttempt, delay, reason);
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 把 RUNNING 交回 WAITING 的唯一实现：transition → applyReset。
     *
     * <p>放弃租约（§5.3）与重试迁移共用它，避免两份几乎同构的分支各自漂移（S9）。
     * 命中的行由调用方决定是否重投；这里只保证「状态与库同时回退」。
     *
     * @return Lua 返回码；只有 {@link JudgeStateService#OK} 时才写库
     */
    private long resetToWaiting(JudgeTaskMessage task, Long epoch, int nextAttempt) {
        long result = transitionWithRecovery(task, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, nextAttempt, null, null, epoch, null);
        if (result == JudgeStateService.OK) {
            JudgeState reset = stateService.getState(task.getSubmitId());
            int version = reset == null ? 0 : reset.getVersion();
            problemSubmitService.applyReset(task.getSubmitId(), version, nextAttempt);
        }
        return result;
    }

    /**
     * 被 fence 的写入统一在这里计数与告警（S8）：三处分支只差 {@code action}/{@code target}。
     */
    private void onFenced(JudgeTaskMessage task, Long epoch, String action, int target) {
        metrics.count("judge.fence.rejected");
        log.warn("evt=judge.fence.rejected submitId={} traceId={} epoch={} target={} action={}",
                task.getSubmitId(), task.getTraceId(), epoch, target, action);
    }

    /**
     * CAS 失败时区分「状态不存在」与「真冲突」：不存在就回源 MySQL 重建一次再试（§6.5）。
     *
     * <p>{@code expectedEpoch} 非空时只有当前 epoch 的持有者能写；{@code newEpoch} 非空时推进 epoch。
     */
    private long transitionWithRecovery(JudgeTaskMessage task, int expectedStatus, int expectedVersion,
            int targetStatus, String transitionWorkerId, Integer attempt, String judgeInfoJson,
            Long leaseUntilMs, Long expectedEpoch, Long newEpoch) {
        long result = stateService.transition(task.getSubmitId(), expectedStatus, expectedVersion,
                targetStatus, transitionWorkerId, attempt, judgeInfoJson, leaseUntilMs, expectedEpoch,
                newEpoch);
        if (result != JudgeStateService.NOT_FOUND) {
            return result;
        }
        JudgeState rebuilt = rebuildState(task.getSubmitId(), task.getTraceId());
        if (rebuilt == null) {
            return result;
        }
        return stateService.transition(task.getSubmitId(), expectedStatus, -1, targetStatus,
                transitionWorkerId, attempt, judgeInfoJson, leaseUntilMs, expectedEpoch, newEpoch);
    }

    private void republish(JudgeTaskMessage task, int nextAttempt) {
        task.setAttempt(nextAttempt);
        long delay = retryDelay(nextAttempt);
        if (!publisher.publishRetry(task, delay)) {
            throw new RetryableJudgeException("重试消息投递失败");
        }
    }

    private long retryDelay(int nextAttempt) {
        long[] delays = properties.getAsync().getRetryDelaysMs();
        int index = Math.min(Math.max(nextAttempt - 2, 0), delays.length - 1);
        return delays[index];
    }

    private int nextAttempt(JudgeTaskMessage task) {
        return (task.getAttempt() == null ? 1 : task.getAttempt()) + 1;
    }

    private boolean isDeleted(ProblemSubmit submit) {
        return submit.getIsDelete() != null && submit.getIsDelete() == 1;
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "judge";
        }
        return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }
}
