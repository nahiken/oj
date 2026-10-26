package com.oj.judge.memory.event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.annotation.Resource;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.oj.judge.metrics.JudgeMetrics;
import com.rabbitmq.client.Channel;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件消费者：一条消息一次落库，手动 ack。
 *
 * <p>刻意不用 `@RabbitListener(queues=...) MissionEvent event` 的 POJO 入参：
 * 那会依赖容器里唯一的 {@code MessageConverter}，而判题链路在 sync 模式下不注册该 Bean。
 * 直接读 body 让记忆链路与判题链路的 Bean 装配解耦。
 *
 * <p>落库失败 nack(requeue=false) 进死信，不阻塞队列——记忆丢一条是可接受的，
 * 卡住整个事件流不是。
 *
 * <p>只在 `enabled=true` 且 `transport=amqp` 时注册：回滚时不能出现「监听一个不再声明的队列」
 * （复审 S2/P3）。
 */
@Slf4j
@Component
@ConditionalOnExpression("'${oj.memory.enabled:true}' == 'true'"
        + " && '${oj.memory.transport:amqp}'.toLowerCase() == 'amqp'")
public class LearningEventConsumer {

    @Resource
    private LearningEventStore store;
    @Resource
    private JudgeMetrics metrics;

    @RabbitListener(queues = LearningMemoryMq.QUEUE_EVENT, ackMode = "MANUAL",
            concurrency = "${oj.memory.consumer-concurrency:1}")
    public void onEvent(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            LearningEventMessage event = JSONUtil.toBean(body, LearningEventMessage.class);
            if (event == null || event.getEventId() == null || event.getUid() == null) {
                metrics.count("memory.event.invalid");
                channel.basicAck(deliveryTag, false);
                return;
            }
            store.append(event);
            metrics.count("memory.event.consumed");
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            metrics.count("memory.event.consume_failed");
            log.warn("evt=memory.event.consume_failed reason={} action=dead_letter", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
