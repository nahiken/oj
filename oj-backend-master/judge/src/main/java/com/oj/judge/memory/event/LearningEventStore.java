package com.oj.judge.memory.event;

import java.util.Date;

import javax.annotation.Resource;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;

import com.oj.judge.memory.mapper.LearningEventMapper;
import com.oj.judge.memory.model.LearningEventDO;
import com.oj.judge.metrics.JudgeMetrics;

import cn.hutool.core.lang.Snowflake;

/**
 * 事件的唯一写入口：MQ 消费者与降级直写都走这里，保证只有一份落库语义。
 *
 * <p>只负责写。会话结束后的聚合不在这里编排：落库成功后发一个进程内事件，
 * 由聚合任务自己决定要不要立刻物化（避免适配层反向依赖 Service 层）。
 */
@Repository
public class LearningEventStore {

    @Resource
    private LearningEventMapper mapper;
    @Resource
    private Snowflake snowflake;
    @Resource
    private JudgeMetrics metrics;
    @Resource
    private ApplicationEventPublisher events;

    /**
     * 写一条事件。重复 `eventId` 视为成功（它本来就在库里），单独计数。
     *
     * @throws RuntimeException 落库失败（由调用方决定 ack / nack / 丢弃）
     */
    public void append(LearningEventMessage message) {
        LearningEventDO row = new LearningEventDO();
        row.setId(snowflake.nextId());
        row.setEventId(message.getEventId());
        row.setUid(message.getUid());
        row.setEventType(message.getEventType());
        row.setProblemId(message.getProblemId() == null ? 0L : message.getProblemId());
        row.setSubmitId(message.getSubmitId() == null ? 0L : message.getSubmitId());
        row.setSource(message.getSource() == null ? LearningEventMessage.SOURCE_SERVER : message.getSource());
        row.setPayload(message.getPayload());
        long occurredAt = message.getOccurredAt() == null ? System.currentTimeMillis() : message.getOccurredAt();
        row.setOccurredAt(new Date(occurredAt));
        row.setCreatedAt(new Date());
        boolean inserted = mapper.insertIgnore(row) > 0;
        if (!inserted) {
            metrics.count("memory.event.duplicate");
            return;
        }
        // accepted = 真正落库的行数（不是 publish 成功的条数）：两者在 MQ 丢消息时会分叉
        metrics.count("memory.event.accepted");
        // 必须在落库成功之后：先物化再落库会生成一个不含触发事件的 Episode（契约 0004 §4.4）
        if (endsSession(row.getEventType())) {
            events.publishEvent(new LearningEventStoredEvent(row.getUid(), row.getProblemId()));
        }
        // 只有真正落库的 AC 才触发 Skill 归因，重复 eventId 在上面已提前返回。
        if (LearningEventType.AC.getValue().equals(row.getEventType())) {
            events.publishEvent(new LearningAcceptedEvent(row.getUid(), row.getProblemId()));
        }
    }

    private boolean endsSession(String eventType) {
        return LearningEventType.AC.getValue().equals(eventType)
                || LearningEventType.DWELL.getValue().equals(eventType);
    }
}
