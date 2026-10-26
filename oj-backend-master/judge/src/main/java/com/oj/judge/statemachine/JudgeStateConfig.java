package com.oj.judge.statemachine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Lua 脚本注册。脚本内容与 docs/design-docs/0001-async-judge.md §6.3–6.5 一致。
 */
@Configuration
public class JudgeStateConfig {

    @Bean
    public DefaultRedisScript<Long> judgeInitScript() {
        return script("lua/judge_init.lua");
    }

    @Bean
    public DefaultRedisScript<Long> judgeTransitionScript() {
        return script("lua/judge_transition.lua");
    }

    @Bean
    public DefaultRedisScript<Long> judgeUnlockScript() {
        return script("lua/judge_unlock.lua");
    }

    @Bean
    public DefaultRedisScript<Long> judgeLeaseRenewScript() {
        return script("lua/judge_lease_renew.lua");
    }

    @Bean
    public DefaultRedisScript<Long> judgeNextEpochScript() {
        return script("lua/judge_next_epoch.lua");
    }

    private DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
