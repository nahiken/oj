package com.oj.judge.mq;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明与消息转换器。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md 第 4 节、0002-judge-pool.md §4.4。
 * 只有 async 模式才声明，sync 回滚时不会创建队列、也不会消费。
 */
@Configuration
@ConditionalOnProperty(prefix = "oj.judge", name = "mode", havingValue = "async", matchIfMissing = true)
public class JudgeRabbitConfig {

    @Bean
    public MessageConverter judgeMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ---------------- 任务入口 ----------------

    @Bean
    public TopicExchange judgeExchange() {
        return new TopicExchange(JudgeMqConstants.EXCHANGE, true, false);
    }

    @Bean
    public Queue judgeQueueNormal() {
        return taskQueue(JudgeMqConstants.QUEUE_NORMAL);
    }

    @Bean
    public Queue judgeQueueHigh() {
        return taskQueue(JudgeMqConstants.QUEUE_HIGH);
    }

    @Bean
    public Queue judgeQueueRun() {
        return taskQueue(JudgeMqConstants.QUEUE_RUN);
    }

    @Bean
    public Binding judgeBindingNormal() {
        return BindingBuilder.bind(judgeQueueNormal()).to(judgeExchange()).with(JudgeMqConstants.RK_NORMAL);
    }

    @Bean
    public Binding judgeBindingHigh() {
        return BindingBuilder.bind(judgeQueueHigh()).to(judgeExchange()).with(JudgeMqConstants.RK_HIGH);
    }

    @Bean
    public Binding judgeBindingRun() {
        return BindingBuilder.bind(judgeQueueRun()).to(judgeExchange()).with(JudgeMqConstants.RK_RUN);
    }

    private Queue taskQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", JudgeMqConstants.DLX)
                .withArgument("x-dead-letter-routing-key", JudgeMqConstants.RK_DEAD)
                .build();
    }

    // ---------------- 死信 ----------------

    @Bean
    public TopicExchange judgeDlx() {
        return new TopicExchange(JudgeMqConstants.DLX, true, false);
    }

    @Bean
    public Queue judgeQueueDead() {
        return QueueBuilder.durable(JudgeMqConstants.QUEUE_DEAD).build();
    }

    @Bean
    public Binding judgeBindingDead() {
        return BindingBuilder.bind(judgeQueueDead()).to(judgeDlx()).with(JudgeMqConstants.RK_DEAD);
    }

    // ---------------- 重试 ----------------
    // 每档优先级各三档延迟：judge.queue.retry.{high,normal,run}.{5s,15s,45s}。
    // TTL 到期后由队列的 x-dead-letter-routing-key 显式送回原优先级任务队列。
    // 必须覆写：DLX 保留的是消息进入重试队列时的 routing key（judge.retry.*），
    // 不覆写就会回到 judge.exchange 后匹配不到任务队列。

    @Bean
    public DirectExchange judgeRetryExchange() {
        return new DirectExchange(JudgeMqConstants.RETRY_EXCHANGE, true, false);
    }

    @Bean
    public Declarables judgeRetryDeclarables(DirectExchange judgeRetryExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (String priority : JudgeMqConstants.PRIORITIES) {
            for (long delayMs : JudgeMqConstants.RETRY_DELAYS_MS) {
                Queue queue = retryQueue(JudgeMqConstants.retryQueueName(priority, delayMs), delayMs,
                        JudgeMqConstants.routingKeyOf(priority));
                declarables.add(queue);
                declarables.add(BindingBuilder.bind(queue).to(judgeRetryExchange)
                        .with(JudgeMqConstants.retryRoutingKey(priority, delayMs)));
            }
        }
        return new Declarables(declarables);
    }

    private Queue retryQueue(String name, long ttlMs, String returnRoutingKey) {
        return QueueBuilder.durable(name)
                .withArgument("x-message-ttl", (int) ttlMs)
                .withArgument("x-dead-letter-exchange", JudgeMqConstants.EXCHANGE)
                .withArgument("x-dead-letter-routing-key", returnRoutingKey)
                .build();
    }
}
