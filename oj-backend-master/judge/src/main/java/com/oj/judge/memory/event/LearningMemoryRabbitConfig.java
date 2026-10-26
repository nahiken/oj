package com.oj.judge.memory.event;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 学习事件 MQ 拓扑（契约 0004 §3）。
 *
 * <p>只在 `oj.memory.enabled=true` 且 `transport=amqp` 时声明：
 * direct 模式不建队列、不起消费者，避免「配了直写却仍有队列堆积」的假象（复审 P3）。
 */
@Configuration
@ConditionalOnExpression("'${oj.memory.enabled:true}' == 'true'"
        + " && '${oj.memory.transport:amqp}'.toLowerCase() == 'amqp'")
public class LearningMemoryRabbitConfig {

    @Bean
    public TopicExchange learningEventExchange() {
        return new TopicExchange(LearningMemoryMq.EXCHANGE, true, false);
    }

    @Bean
    public Queue learningEventQueue() {
        return QueueBuilder.durable(LearningMemoryMq.QUEUE_EVENT)
                .withArgument("x-dead-letter-exchange", LearningMemoryMq.DLX)
                .withArgument("x-dead-letter-routing-key", LearningMemoryMq.RK_DEAD)
                .build();
    }

    @Bean
    public Binding learningEventBinding() {
        return BindingBuilder.bind(learningEventQueue()).to(learningEventExchange())
                .with(LearningMemoryMq.RK_EVENT);
    }

    @Bean
    public TopicExchange learningDlx() {
        return new TopicExchange(LearningMemoryMq.DLX, true, false);
    }

    @Bean
    public Queue learningEventDeadQueue() {
        return QueueBuilder.durable(LearningMemoryMq.QUEUE_DEAD).build();
    }

    @Bean
    public Binding learningEventDeadBinding() {
        return BindingBuilder.bind(learningEventDeadQueue()).to(learningDlx())
                .with(LearningMemoryMq.RK_DEAD);
    }
}
