package com.oj.judge.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.lock.JudgeLeaseManager;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.mq.JudgeMessagePublisher;
import com.oj.judge.service.JudgeMetricsService;

/**
 * 指标快照组装：把 {@link JudgeMetrics} 与异步发布队列、租约的计数合成一个 Map。
 *
 * <p>契约：docs/design-docs/0002-judge-pool.md §2、§4；0003-lease-fencing.md §8。
 */
@Service
public class JudgeMetricsServiceImpl implements JudgeMetricsService {

    @Resource
    private JudgeMetrics metrics;
    @Resource
    private JudgeMessagePublisher publisher;
    @Resource
    private JudgeLeaseManager leaseManager;
    @Resource
    private JudgeAsyncProperties properties;

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = metrics.snapshot();
        Map<String, Object> publish = new LinkedHashMap<>();
        publish.put("accepted", publisher.asyncAccepted());
        publish.put("published", publisher.asyncPublished());
        publish.put("failed", publisher.asyncFailed());
        publish.put("rejected", publisher.asyncRejected());
        publish.put("queueDepth", publisher.asyncQueueDepth());
        snapshot.put("publish", publish);

        // 租约参数放进指标：压测脚本据此确认「这轮实验确实是短 TTL / 续租开」，
        // 避免改了命令行却跑在旧配置上还不知道（P3 的实验依赖这个自检）。
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("ttlMs", properties.getAsync().getLeaseTtlMs());
        lease.put("renewIntervalMs", properties.getAsync().effectiveLeaseRenewIntervalMs());
        lease.put("renewEnabled", properties.getAsync().isLeaseRenewEnabled());
        lease.put("active", leaseManager.activeCount());
        snapshot.put("lease", lease);
        return snapshot;
    }
}
