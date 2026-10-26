package com.oj.judge.memory.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.support.EventPayloads;
import com.oj.judge.metrics.JudgeMetrics;

import cn.hutool.core.convert.Convert;
import lombok.extern.slf4j.Slf4j;

/**
 * 学习事件记录器：所有埋点的唯一入口（契约 0004 §2、§3）。
 *
 * <p><b>旁路保证</b>：请求线程只做「构造信封 + 丢进有界队列」，序列化、MQ 投递、降级直写
 * 都在单线程 worker 上完成。队列满 → 丢弃并计数，绝不阻塞提交/判题/运行。
 *
 * <p><b>失败不可见</b>：任何异常都在这里被吞掉并计数，调用方拿不到异常，也就不会因为埋点失败
 * 改变业务结果。客户端上报超限同样只丢弃 + 计数，**不返回错误**（复审 S1/P5）。
 */
@Slf4j
@Component
public class LearningEventRecorder {

    /** 进程内事件队列容量：按「每秒几十条、worker 千条/秒」估，满即丢 */
    private static final int QUEUE_CAPACITY = 8192;

    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private LearningEventPublisher publisher;
    @Resource
    private LearningEventStore store;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JudgeMetrics metrics;

    private ThreadPoolExecutor executor;

    @PostConstruct
    public void init() {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "memory-event");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------- 服务端发射点 ----------------

    /** 有题目、无提交的事件（view_problem / run）。 */
    public void recordForProblem(LearningEventType type, Long uid, Long problemId, Map<String, Object> payload) {
        dispatch(type, uid, problemId, 0L, payload, LearningEventMessage.SOURCE_SERVER, null, null);
    }

    /** 与某次提交绑定的判题结果事件（submit / ac / wa）。 */
    public void recordForSubmit(LearningEventType type, Long uid, Long problemId, Long submitId,
            Map<String, Object> payload) {
        dispatch(type, uid, problemId, submitId, payload, LearningEventMessage.SOURCE_SERVER, null, null);
    }

    // ---------------- 客户端上报 ----------------

    /**
     * @return 实际接收的条数；超限的条目丢弃并计数，**不抛异常**（契约 0004 §1、§3）
     */
    public int recordClient(Long uid, List<LearningEventReportReq.Item> items) {
        if (!properties.isEnabled()) {
            return 0;
        }
        int accepted = 0;
        for (LearningEventReportReq.Item item : items) {
            LearningEventType type = LearningEventType.fromClientValue(item.getEventType());
            if (type == null) {
                metrics.count("memory.event.client_rejected");
                continue;
            }
            if (!allowClientEvent(uid)) {
                // 超限：丢弃该条及其后的同批条目，只计数——埋点失败不得向用户报错
                metrics.count("memory.event.rate_limited");
                metrics.count("memory.event.dropped");
                break;
            }
            dispatch(type, uid, item.getProblemId(), 0L, sanitize(type, item),
                    LearningEventMessage.SOURCE_CLIENT, item.getEventId(), item.getClientTime());
            accepted++;
        }
        return accepted;
    }

    // ---------------- 内部 ----------------

    /**
     * 客户端 payload 白名单：只保留该类型需要的数值字段。
     *
     * <p>不直接信任浏览器传来的对象：放任会变成「任意 JSON 写库」，也会让 payload 长度失控。
     */
    private Map<String, Object> sanitize(LearningEventType type, LearningEventReportReq.Item item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> raw = item.getPayload();
        if (raw != null) {
            if (type == LearningEventType.COPY_CODE) {
                payload.put("chars", clamp(Convert.toLong(raw.get("chars"), 0L), 0L, 200000L));
            } else if (type == LearningEventType.DWELL) {
                payload.put("durationMs", clamp(Convert.toLong(raw.get("durationMs"), 0L), 0L, 7200000L));
            }
        }
        // 契约 0004 §2.2：客户端时间只作参考，不参与聚合
        if (item.getClientTime() != null) {
            payload.put("clientTime", item.getClientTime());
        }
        return payload;
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean allowClientEvent(Long uid) {
        try {
            long minute = System.currentTimeMillis() / 60000L;
            String key = "memory:rate:" + uid + ":" + minute;
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, java.time.Duration.ofSeconds(90));
            }
            return count == null || count <= properties.getClientEventsPerMinute();
        } catch (Exception e) {
            // Redis 抖动不该让埋点失败：放行，靠总量监控兜底
            return true;
        }
    }

    private void dispatch(LearningEventType type, Long uid, Long problemId, Long submitId,
            Map<String, Object> payload, String source, String eventId, Long clientTime) {
        if (!properties.isEnabled() || type == null || uid == null || executor == null) {
            return;
        }
        try {
            LearningEventMessage message = build(type, uid, problemId, submitId, payload, source,
                    eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId, clientTime);
            executor.execute(() -> deliver(message));
        } catch (Exception e) {
            metrics.count("memory.event.dropped");
            log.warn("evt=memory.event.enqueue_failed type={} uid={} reason={}", type.getValue(), uid,
                    e.getMessage());
        }
    }

    private LearningEventMessage build(LearningEventType type, Long uid, Long problemId, Long submitId,
            Map<String, Object> payload, String source, String eventId, Long clientTime) {
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : payload;
        if (clientTime != null && !body.containsKey("clientTime")) {
            body.put("clientTime", clientTime);
        }
        return LearningEventMessage.builder()
                .eventId(eventId)
                .uid(uid)
                .eventType(type.getValue())
                .problemId(problemId == null ? 0L : problemId)
                .submitId(submitId == null ? 0L : submitId)
                .source(source)
                .payload(EventPayloads.toJson(body, properties.getPayloadMaxBytes()))
                .occurredAt(System.currentTimeMillis())
                .build();
    }

    /**
     * worker 线程：优先 MQ，投递失败（含 broker 未确认）降级直写；两条路都失败才丢。
     */
    private void deliver(LearningEventMessage message) {
        try {
            if (properties.isAmqp()) {
                if (publisher.publish(message)) {
                    metrics.count("memory.event.published");
                    return;
                }
                // broker 没确认 / 抛异常：降级直写，避免「以为发出去了」的丢事件
                metrics.count("memory.event.publish_failed");
            }
            // Store 负责计 accepted（落库行数），这里不再重复计数
            store.append(message);
        } catch (Exception e) {
            metrics.count("memory.event.dropped");
            log.warn("evt=memory.event.deliver_failed eventId={} type={} reason={}",
                    message.getEventId(), message.getEventType(), e.getMessage());
        }
    }
}
