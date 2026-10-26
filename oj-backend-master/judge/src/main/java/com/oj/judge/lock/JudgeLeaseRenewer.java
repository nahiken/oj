package com.oj.judge.lock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.oj.judge.config.JudgeAsyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 看门狗：单线程按 {@code lease-renew-interval-ms} 续租本进程的活跃租约。
 *
 * <p>用独立调度器而不是共享的 {@code @Scheduled} 池：对账/老化任务可能长时间占用调度线程，
 * 续租被拖住就会误接管。线程是 daemon，不阻塞停机。
 *
 * <p>契约：docs/design-docs/0003-lease-fencing.md §5.1。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.judge.async", name = "lease-renew-enabled",
        havingValue = "true", matchIfMissing = true)
public class JudgeLeaseRenewer {

    @Resource
    private JudgeLeaseManager leaseManager;
    @Resource
    private JudgeAsyncProperties properties;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        long interval = intervalMs(properties);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "judge-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("evt=judge.lease.renewer.start intervalMs={} ttlMs={}", interval,
                properties.getAsync().getLeaseTtlMs());
    }

    /**
     * 调度周期与 {@link JudgeLeaseManager} 的 {@code renewAt} 归一化到同一个值（契约 0003 §7）。
     */
    static long intervalMs(JudgeAsyncProperties properties) {
        return properties.getAsync().effectiveLeaseRenewIntervalMs();
    }

    void tick() {
        try {
            leaseManager.renewDue();
        } catch (Exception e) {
            log.error("evt=judge.lease.renew_error", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
