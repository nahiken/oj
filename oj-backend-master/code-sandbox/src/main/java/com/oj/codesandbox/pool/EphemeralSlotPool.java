package com.oj.codesandbox.pool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 不复用的池：每次借约新建、归还即销毁。
 *
 * <p>用于 {@code oj.sandbox.pool.enabled=false}，行为与 P1「每任务新建工作区」一致，
 * 便于 A/B 对比与回滚；并发上限交给判题侧线程池。
 */
public class EphemeralSlotPool implements SandboxSlotPool {

    private final SandboxSlotFactory factory;
    private final PoolMetrics metrics = new PoolMetrics();
    private final AtomicInteger sequence = new AtomicInteger();

    public EphemeralSlotPool(SandboxSlotFactory factory) {
        this.factory = factory;
    }

    @Override
    public SandboxSlot borrow() {
        SandboxSlot slot = factory.create("ephemeral-" + sequence.incrementAndGet());
        metrics.recordCreated();
        metrics.recordBorrow(0L);
        return slot;
    }

    @Override
    public void release(SandboxSlot slot, boolean reusable) {
        try {
            slot.destroy();
        } finally {
            metrics.recordDiscarded();
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("backend", factory.backend());
        map.put("mode", "ephemeral");
        map.put("size", 0);
        map.put("idle", 0);
        map.put("inFlight", 0);
        map.putAll(metrics.snapshot());
        return map;
    }

    @Override
    public String backend() {
        return factory.backend();
    }
}
