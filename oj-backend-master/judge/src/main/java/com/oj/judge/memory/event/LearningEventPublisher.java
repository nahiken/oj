package com.oj.judge.memory.event;

import javax.annotation.Resource;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 事件生产者。
 *
 * <p>开启 publisher confirm 时**等 broker 确认**：没确认或投递抛异常都返回 false，
 * 由 Recorder 降级直写（复审 P4——不等确认时「降级」永不触发）。
 *
 * <p>未开启 confirm 的连接工厂保持 fire-and-forget：此时调用 `waitForConfirms` 会白等到超时，
 * 反而把每条事件拖慢 2s。
 */
@Slf4j
@Component
public class LearningEventPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 2000L;

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ConnectionFactory connectionFactory;

    /**
     * @return false 表示投递失败（调用方降级直写）
     */
    public boolean publish(LearningEventMessage event) {
        try {
            if (!confirmsEnabled()) {
                rabbitTemplate.convertAndSend(LearningMemoryMq.EXCHANGE, LearningMemoryMq.RK_EVENT, event,
                        this::persistent);
                return true;
            }
            Boolean confirmed = rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(LearningMemoryMq.EXCHANGE, LearningMemoryMq.RK_EVENT, event,
                        this::persistent);
                return operations.waitForConfirms(CONFIRM_TIMEOUT_MS);
            });
            if (!Boolean.TRUE.equals(confirmed)) {
                log.warn("evt=memory.event.publish_unconfirmed eventId={} type={} uid={}",
                        event.getEventId(), event.getEventType(), event.getUid());
            }
            return Boolean.TRUE.equals(confirmed);
        } catch (Exception e) {
            log.warn("evt=memory.event.publish_failed eventId={} type={} uid={} reason={}",
                    event.getEventId(), event.getEventType(), event.getUid(), e.getMessage());
            return false;
        }
    }

    private boolean confirmsEnabled() {
        return connectionFactory instanceof CachingConnectionFactory
                && ((CachingConnectionFactory) connectionFactory).isPublisherConfirms();
    }

    private org.springframework.amqp.core.Message persistent(org.springframework.amqp.core.Message message) {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return message;
    }
}
