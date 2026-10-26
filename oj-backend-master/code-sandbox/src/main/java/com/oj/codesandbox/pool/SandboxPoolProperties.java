package com.oj.codesandbox.pool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 沙箱池参数。契约定额见 {@code docs/design-docs/0002-judge-pool.md} §3.3。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oj.sandbox.pool")
public class SandboxPoolProperties {

    /** false 时退回「每任务新建」，用于 A/B 与回滚。 */
    private boolean enabled = true;

    /** 同时可执行的槽位数，决定判题并发上限与背压强度。 */
    private int size = 8;

    /** 借约等待上限，超时返回「判题繁忙」。 */
    private long borrowTimeoutMs = 30000L;

    /** 单个槽位复用次数上限，超过后销毁重建。 */
    private int reuseLimit = 200;

    /** 空闲槽位健康检查间隔。 */
    private long healthCheckMs = 30000L;
}
