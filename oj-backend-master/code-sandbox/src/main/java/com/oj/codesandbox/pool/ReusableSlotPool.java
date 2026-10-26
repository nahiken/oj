package com.oj.codesandbox.pool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import lombok.extern.slf4j.Slf4j;

/**
 * 固定大小借还池。
 *
 * <p>启动时创建 {@code size} 个 slot；借约从空闲队列取，归还先清理再放回；
 * 销毁的槽位由后台补建任务补齐（创建失败下一轮重试，不影响已有槽位）。
 *
 * <p>池大小就是并发上限：拿不到槽位说明系统已经满载，调用方按「判题繁忙」背压。
 */
@Slf4j
public class ReusableSlotPool implements SandboxSlotPool, InitializingBean, DisposableBean {

    private final SandboxSlotFactory factory;
    private final SandboxPoolProperties properties;
    private final PoolMetrics metrics = new PoolMetrics();

    private final BlockingQueue<SandboxSlot> idle = new LinkedBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    /** 活着的槽位总数（idle + 借出 + 巡检中）。补建只看它，才不会在巡检窗口里超建。 */
    private final AtomicInteger live = new AtomicInteger();
    /** 已借出未归还的槽位：用于让 release 幂等（双归还会重复入队并多减 inFlight）。 */
    private final Set<SandboxSlot> borrowed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sandbox-pool");
        thread.setDaemon(true);
        return thread;
    });

    public ReusableSlotPool(SandboxSlotFactory factory, SandboxPoolProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        int size = Math.max(1, properties.getSize());
        for (int i = 0; i < size; i++) {
            idle.offer(createSlot());
        }
        maintenance.scheduleWithFixedDelay(this::replenishQuietly, 1000, 1000, TimeUnit.MILLISECONDS);
        long healthCheck = Math.max(1000L, properties.getHealthCheckMs());
        maintenance.scheduleWithFixedDelay(this::healthCheckQuietly, healthCheck, healthCheck, TimeUnit.MILLISECONDS);
        log.info("evt=sandbox.pool.start backend={} mode=reusable size={} borrowTimeoutMs={} reuseLimit={}",
                factory.backend(), size, properties.getBorrowTimeoutMs(), properties.getReuseLimit());
    }

    @Override
    public SandboxSlot borrow() {
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(properties.getBorrowTimeoutMs());
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                metrics.recordTimeout();
                throw new SandboxBusyException("沙箱池繁忙，等待槽位超时");
            }
            SandboxSlot slot;
            try {
                slot = idle.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.recordTimeout();
                throw new SandboxBusyException("借约被中断");
            }
            if (slot == null) {
                metrics.recordTimeout();
                throw new SandboxBusyException("沙箱池繁忙，等待槽位超时");
            }
            if (!slot.healthy()) {
                log.warn("evt=sandbox.pool.unhealthy slot={} action=recreate", slot.id());
                destroySlot(slot);
                maintenance.execute(this::replenishQuietly);
                continue;
            }
            inFlight.incrementAndGet();
            borrowed.add(slot);
            slot.markUsed();
            metrics.recordBorrow((System.nanoTime() - start) / 1_000_000L);
            return slot;
        }
    }

    @Override
    public void release(SandboxSlot slot, boolean reusable) {
        // 幂等：不接受「没借出的槽位」或「已归还过的槽位」，否则会重复入 idle、多减 inFlight
        if (!borrowed.remove(slot)) {
            metrics.recordDoubleRelease();
            log.warn("evt=sandbox.pool.release_ignored slot={} reason=not_borrowed_or_already_released", slot.id());
            return;
        }
        boolean returned = false;
        if (reusable && slot.useCount() < properties.getReuseLimit()) {
            try {
                slot.reset();
                returned = idle.offer(slot);
            } catch (Exception e) {
                log.warn("evt=sandbox.pool.reset_failed slot={} action=recreate", slot.id(), e);
            }
        }
        inFlight.decrementAndGet();
        if (returned) {
            metrics.recordReused();
        } else {
            destroySlot(slot);
            maintenance.execute(this::replenishQuietly);
        }
    }

    /**
     * 补齐到 {@code size}。包内可见是给单测用的：要能构造「槽位既不在 idle 也不在 inFlight」
     * 的窗口（借约刚 poll 出来、或巡检把 idle 整体 drain 出来 probe 的时候），
     * 验证缺口按 live 算而不是按队列长度算。
     */
    synchronized void replenish() {
        int capacity = Math.max(1, properties.getSize());
        // 用「活着的槽位数」而不是 idle.size() 算缺口：借约在 poll 与 inFlight+1 之间、巡检在
        // drain 期间，槽位都不在 idle 里，旧算法会误判缺口并真的多建容器（实测 40 条任务后
        // pool-9/pool-10 多出来、容器数超出 size）。
        int missing = capacity - live.get();
        for (int i = 0; i < missing; i++) {
            try {
                idle.offer(createSlot());
            } catch (Exception e) {
                log.error("evt=sandbox.pool.create_failed backend={}", factory.backend(), e);
                return;
            }
        }
    }

    private void replenishQuietly() {
        try {
            replenish();
        } catch (Exception e) {
            log.error("evt=sandbox.pool.replenish_error", e);
        }
    }

    private void healthCheckQuietly() {
        try {
            List<SandboxSlot> slots = new ArrayList<>();
            idle.drainTo(slots);
            int unhealthy = 0;
            for (SandboxSlot slot : slots) {
                // 巡检用 probe()：只在空闲时做容器内写探针，不进借约热路径
                if (slot.probe()) {
                    idle.offer(slot);
                } else {
                    unhealthy++;
                    log.warn("evt=sandbox.pool.unhealthy slot={} action=recreate", slot.id());
                    destroySlot(slot);
                }
            }
            if (unhealthy > 0) {
                log.warn("evt=sandbox.pool.health_check unhealthy={} action=recreate", unhealthy);
                replenish();
            }
        } catch (Exception e) {
            log.error("evt=sandbox.pool.health_check_error", e);
        }
    }

    private SandboxSlot createSlot() {
        SandboxSlot slot = factory.create("pool-" + sequence.incrementAndGet());
        live.incrementAndGet();
        metrics.recordCreated();
        return slot;
    }

    private void destroySlot(SandboxSlot slot) {
        try {
            slot.destroy();
        } catch (Exception e) {
            log.warn("evt=sandbox.pool.destroy_failed slot={}", slot.id(), e);
        }
        live.decrementAndGet();
        metrics.recordDiscarded();
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("backend", factory.backend());
        map.put("mode", "reusable");
        map.put("size", properties.getSize());
        map.put("idle", idle.size());
        map.put("inFlight", inFlight.get());
        map.put("live", live.get());
        map.putAll(metrics.snapshot());
        map.put("slots", slotSnapshot());
        return map;
    }

    /**
     * 每个活着的槽位一行，带上容器 ID。
     *
     * <p>「容器被复用」不能靠 created/reused 计数推断：计数只说明池没重建，说明不了同一个
     * 容器在连续接任务。给出一致的 containerId 才能直接对账（docker ps 也查得到同一个 ID）。
     */
    private List<Map<String, Object>> slotSnapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SandboxSlot slot : idle) {
            list.add(describeSlot(slot, "idle"));
        }
        for (SandboxSlot slot : borrowed) {
            list.add(describeSlot(slot, "borrowed"));
        }
        return list;
    }

    private Map<String, Object> describeSlot(SandboxSlot slot, String state) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", slot.id());
        item.put("containerId", slot.containerId());
        item.put("useCount", slot.useCount());
        item.put("state", state);
        return item;
    }

    @Override
    public String backend() {
        return factory.backend();
    }

    @Override
    public void destroy() {
        maintenance.shutdownNow();
        List<SandboxSlot> slots = new ArrayList<>();
        idle.drainTo(slots);
        for (SandboxSlot slot : slots) {
            destroySlot(slot);
        }
        log.info("evt=sandbox.pool.stop backend={} created={} reused={} discarded={}", factory.backend(),
                metrics.snapshot().get("created"), metrics.snapshot().get("reused"),
                metrics.snapshot().get("discarded"));
    }
}
