package com.oj.judge.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oj.judge.config.JudgeAsyncProperties;

/**
 * 判题执行调度器装配。
 *
 * <p>只有一个池：三个优先级档位共享固定大小的工作线程（详见 {@link WeightedJudgeDispatcher}）。
 * 需要「正式提交与自测互不拖垮」时，靠档位隔离 + 加权派发，而不是靠两个互不相通的线程池，
 * 否则空闲档位会白白浪费容量。
 */
@Configuration
@ConditionalOnProperty(prefix = "oj.judge", name = "mode", havingValue = "async", matchIfMissing = true)
public class JudgeExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public WeightedJudgeDispatcher judgeDispatcher(JudgeAsyncProperties properties) {
        return new WeightedJudgeDispatcher(properties);
    }
}
