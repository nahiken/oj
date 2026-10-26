package com.oj.judge.mq;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.oj.judge.config.JudgeAsyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 判题消息生产者：publisher confirm + mandatory return。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §4.3、docs/design-docs/0002-judge-pool.md §2。
 *
 * <p>提交路径走 {@link #publishAsync}：confirm 要等 broker 把持久化消息落盘，实测单次
 * 70ms 以上（120 并发下 300ms+），放在请求线程里会把提交 P95 拖到秒级。请求线程只做
 * 「落库 + Redis + 投递到本地有界队列」，confirm 由后台线程等待；投递失败/队列满时
 * 提交行保持 WAITING，由对账任务重投，不丢任务。
 *
 * <p>注意：本类不能加 {@code @ConditionalOnProperty}——sync 回滚模式下
 * {@code ProblemSubmitServiceImpl} 仍会注入它（只是不调用）；不加条件才能让回滚开关真正可用。
 */
@Slf4j
@Component
public class JudgeMessagePublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private JudgeAsyncProperties properties;

    /** 被 broker 退回的 msgId，发送方确认后消费掉 */
    private final Set<String> returnedMsgIds = ConcurrentHashMap.newKeySet();

    /** 异步发布队列：满即拒绝，由对账任务兜底 */
    private ThreadPoolExecutor publishExecutor;

    private final AtomicLong asyncAccepted = new AtomicLong();
    private final AtomicLong asyncPublished = new AtomicLong();
    private final AtomicLong asyncFailed = new AtomicLong();
    private final AtomicLong asyncRejected = new AtomicLong();

    @Value("${oj.judge.async.async-publish-threads:2}")
    private int publishThreads;
    @Value("${oj.judge.async.async-publish-queue:8192}")
    private int publishQueueSize;

    @PostConstruct
    public void initReturnsCallback() {
        rabbitTemplate.setReturnsCallback(returned -> {
            Object msgId = returned.getMessage().getMessageProperties().getHeaders()
                    .get(JudgeMqConstants.HEADER_MSG_ID);
            if (msgId != null) {
                returnedMsgIds.add(msgId.toString());
            }
            log.error("evt=judge.publish.returned exchange={} rk={} replyCode={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(),
                    returned.getReplyText());
        });

        publishExecutor = new ThreadPoolExecutor(publishThreads, publishThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(publishQueueSize), runnable -> {
                    Thread thread = new Thread(runnable, "judge-publisher");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 请求线程调用：只把任务交给本地发布队列，不等 broker confirm。
     *
     * @return false 表示本地队列已满（此时提交行保持 WAITING，由对账重投）
     */
    public boolean publishAsync(JudgeTaskMessage task) {
        if (publishExecutor == null) {
            return publish(task);
        }
        try {
            publishExecutor.execute(() -> {
                if (publish(task)) {
                    asyncPublished.incrementAndGet();
                } else {
                    asyncFailed.incrementAndGet();
                    log.error("evt=judge.publish.async_failed submitId={} traceId={} msgId={} action=leave_waiting",
                            task.getSubmitId(), task.getTraceId(), task.getMsgId());
                }
            });
            asyncAccepted.incrementAndGet();
            return true;
        } catch (Exception e) {
            asyncRejected.incrementAndGet();
            log.error("evt=judge.publish.rejected submitId={} traceId={} queue={} action=leave_waiting",
                    task.getSubmitId(), task.getTraceId(), publishQueueSize);
            return false;
        }
    }

    public long asyncAccepted() {
        return asyncAccepted.get();
    }

    public long asyncPublished() {
        return asyncPublished.get();
    }

    public long asyncFailed() {
        return asyncFailed.get();
    }

    public long asyncRejected() {
        return asyncRejected.get();
    }

    public int asyncQueueDepth() {
        return publishExecutor == null ? 0 : publishExecutor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        if (publishExecutor == null) {
            return;
        }
        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("evt=judge.publish.shutdown_timeout pending={}", publishExecutor.getQueue().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 投递任务到 judge.exchange。
     *
     * @return true 表示 broker 已确认接收且未被退回
     */
    public boolean publish(JudgeTaskMessage task) {
        // routingKeyOf 内部已做 normalize（未知/null → NORMAL），不需要再包一层
        return doSend(JudgeMqConstants.EXCHANGE, JudgeMqConstants.routingKeyOf(task.getPriority()), task);
    }

    /**
     * 投递到 TTL 重试队列，到期后由队列声明送回**原优先级**任务队列。
     *
     * @param delayMs 期望延迟；实现按 5s/15s/45s 三档向上取整
     */
    public boolean publishRetry(JudgeTaskMessage task, long delayMs) {
        task.setMsgId(UUID.randomUUID().toString());
        task.setAttempt(task.getAttempt() == null ? 1 : task.getAttempt());
        String priority = JudgeMqConstants.normalizePriority(task.getPriority());
        return doSend(JudgeMqConstants.RETRY_EXCHANGE, JudgeMqConstants.retryRoutingKey(priority, delayMs), task);
    }

    private boolean doSend(String exchange, String routingKey, JudgeTaskMessage task) {
        CorrelationData correlation = new CorrelationData(task.getMsgId());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, task, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setHeader(JudgeMqConstants.HEADER_MSG_ID, task.getMsgId());
                message.getMessageProperties().setHeader(JudgeMqConstants.HEADER_ATTEMPT, task.getAttempt());
                message.getMessageProperties().setHeader(JudgeMqConstants.HEADER_ENQUEUED_AT, task.getEnqueuedAt());
                message.getMessageProperties().setHeader(JudgeMqConstants.HEADER_TRACE_ID, task.getTraceId());
                return message;
            }, correlation);

            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getAsync().getPublishConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.error("evt=judge.publish.nack submitId={} msgId={} traceId={} reason={}",
                        task.getSubmitId(), task.getMsgId(), task.getTraceId(),
                        confirm == null ? "null-confirm" : confirm.getReason());
                return false;
            }
            if (returnedMsgIds.remove(task.getMsgId())) {
                log.error("evt=judge.publish.failed submitId={} msgId={} traceId={} reason=returned",
                        task.getSubmitId(), task.getMsgId(), task.getTraceId());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("evt=judge.publish.interrupted submitId={} traceId={}",
                    task.getSubmitId(), task.getTraceId(), e);
            return false;
        } catch (Exception e) {
            log.error("evt=judge.publish.failed submitId={} msgId={} traceId={} exchange={} rk={}",
                    task.getSubmitId(), task.getMsgId(), task.getTraceId(), exchange, routingKey, e);
            return false;
        }
    }
}
