package com.oj.codesandbox.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 借还池行为单测：借、还、超时、销毁重建、复用上限、指标。
 *
 * <p>用 fake 槽位覆盖池逻辑（不依赖 Docker），Docker 后端只需实现同一接口。
 * 契约：docs/design-docs/0002-judge-pool.md §3。
 */
class ReusableSlotPoolTest {

    @TempDir
    Path tempDir;

    @Test
    void borrowReturnsSlotAndReleaseReusesIt() {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 1000L, 10);
        pool.afterPropertiesSet();

        SandboxSlot first = pool.borrow();
        assertNotNull(first);
        assertEquals(1, first.useCount());
        assertTrue(factory.created.size() >= 1);

        pool.release(first, true);
        SandboxSlot second = pool.borrow();
        assertSame(first, second, "归还后的槽位应被复用");
        assertEquals(2, second.useCount());
        pool.destroy();
    }

    @Test
    void borrowTimesOutWhenPoolExhausted() {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 80L, 10);
        pool.afterPropertiesSet();

        SandboxSlot slot = pool.borrow();
        long start = System.currentTimeMillis();
        assertThrows(SandboxBusyException.class, pool::borrow);
        long waited = System.currentTimeMillis() - start;
        assertTrue(waited >= 60L, "应等待到借约超时，实际 " + waited + "ms");

        pool.release(slot, true);
        pool.destroy();
    }

    @Test
    void releaseNotReusableDestroysSlotAndReplenishes() {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 1000L, 10);
        pool.afterPropertiesSet();

        SandboxSlot slot = pool.borrow();
        pool.release(slot, false);
        assertTrue(((FakeSlot) slot).destroyed, "不可复用的槽位必须销毁");

        // 补建是后台任务，给它一点时间，然后确认池仍有容量
        SandboxSlot replacement = waitForBorrow(pool, 2000L);
        assertNotNull(replacement);
        assertFalse(replacement == slot);
        pool.release(replacement, true);
        pool.destroy();
    }

    @Test
    void releaseAtReuseLimitDiscardsInsteadOfReusing() {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 2000L, 1);
        pool.afterPropertiesSet();

        SandboxSlot first = pool.borrow();
        pool.release(first, true);
        assertTrue(((FakeSlot) first).destroyed, "达到复用上限应销毁而不是放回池");

        SandboxSlot second = waitForBorrow(pool, 2000L);
        assertNotNull(second);
        pool.release(second, true);
        pool.destroy();
    }

    @Test
    void unhealthyIdleSlotIsReplacedOnHealthCheck() throws Exception {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 5000L, 10);
        pool.afterPropertiesSet();

        FakeSlot idle = (FakeSlot) pool.borrow();
        pool.release(idle, true);
        idle.healthy = false;

        // 借到不健康的槽位应销毁并换一个（补建由后台任务完成，借约会等它）
        SandboxSlot next = pool.borrow();
        assertTrue(next != idle, "不健康槽位不应再被借出");
        assertTrue(idle.destroyed);
        pool.release(next, true);
        pool.destroy();
    }

    @Test
    void doubleReleaseIsIgnored() {
        // 契约 §3「归还幂等」：同一槽位归还两次不能让它重复进入 idle，也不能把 inFlight 减两次
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 1000L, 10);
        pool.afterPropertiesSet();

        SandboxSlot slot = pool.borrow();
        pool.release(slot, true);
        pool.release(slot, true);

        java.util.Map<String, Object> snapshot = pool.snapshot();
        assertEquals(1, snapshot.get("idle"), "双归还后 idle 仍然只能有 1 个槽位");
        assertEquals(0, snapshot.get("inFlight"));
        assertEquals(1L, snapshot.get("doubleRelease"));
        assertEquals(1L, snapshot.get("reused"), "第二次归还不应再次计入复用");

        SandboxSlot next = pool.borrow();
        assertSame(slot, next);
        pool.release(next, true);
        pool.destroy();
    }

    @Test
    void concurrentBorrowAndReleaseKeepsPoolConsistent() throws Exception {
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 2, 5000L, 10000);
        pool.afterPropertiesSet();

        int threads = 8;
        int iterations = 40;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    SandboxSlot slot = pool.borrow();
                    pool.release(slot, true);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        java.util.Map<String, Object> snapshot = pool.snapshot();
        assertEquals(2, snapshot.get("idle"), "并发借还结束后槽位应全部回到 idle");
        assertEquals(0, snapshot.get("inFlight"));
        assertEquals(0L, snapshot.get("doubleRelease"));
        assertEquals((long) threads * iterations, snapshot.get("borrowCount"));
        pool.destroy();
    }

    @Test
    void snapshotExposesCounters() {        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 2, 1000L, 10);
        pool.afterPropertiesSet();

        SandboxSlot a = pool.borrow();
        SandboxSlot b = pool.borrow();
        pool.release(a, true);
        pool.release(b, true);

        java.util.Map<String, Object> snapshot = pool.snapshot();
        assertEquals("native", snapshot.get("backend"));
        assertEquals("reusable", snapshot.get("mode"));
        assertEquals(2, snapshot.get("size"));
        assertEquals(2L, snapshot.get("borrowCount"));
        assertEquals(2L, snapshot.get("reused"));
        pool.destroy();
    }

    @Test
    void snapshotExposesTheSameContainerIdAcrossReuse() {
        // 「容器被复用」的证据：同一个槽位连续接两次任务，containerId 不变、useCount 递增
        FakeFactory factory = new FakeFactory(tempDir);
        ReusableSlotPool pool = newPool(factory, 1, 1000L, 100);
        pool.afterPropertiesSet();

        SandboxSlot slot = pool.borrow();
        String firstContainer = slot.containerId();
        pool.release(slot, true);
        SandboxSlot again = pool.borrow();
        pool.release(again, true);

        assertEquals(firstContainer, again.containerId(), "复用的槽位必须还是同一个容器");
        List<?> slots = (List<?>) pool.snapshot().get("slots");
        assertEquals(1, slots.size());
        java.util.Map<?, ?> item = (java.util.Map<?, ?>) slots.get(0);
        assertEquals("pool-1", item.get("id"));
        assertEquals(firstContainer, item.get("containerId"));
        assertEquals(2, item.get("useCount"));
        assertEquals("idle", item.get("state"));
        pool.destroy();
    }

    @Test
    void periodicHealthCheckUsesDeepProbe() throws Exception {
        // 巡检走 probe()：容器在跑（healthy=true）但写探针失败时，空闲槽位必须被销毁重建
        FakeFactory factory = new FakeFactory(tempDir);
        SandboxPoolProperties properties = new SandboxPoolProperties();
        properties.setSize(1);
        properties.setBorrowTimeoutMs(5000L);
        properties.setReuseLimit(10);
        properties.setHealthCheckMs(1000L);
        ReusableSlotPool pool = new ReusableSlotPool(factory, properties);
        pool.afterPropertiesSet();

        SandboxSlot slot = pool.borrow();
        String brokenContainer = slot.containerId();
        pool.release(slot, true);
        ((FakeSlot) slot).probeHealthy = false;

        long deadline = System.currentTimeMillis() + 4000L;
        while (System.currentTimeMillis() < deadline && !((FakeSlot) slot).destroyed) {
            Thread.sleep(100L);
        }
        assertTrue(((FakeSlot) slot).destroyed, "写探针失败的槽位应在巡检时被销毁");

        SandboxSlot replacement = waitForBorrow(pool, 3000L);
        assertNotNull(replacement);
        assertNotEquals(brokenContainer, replacement.containerId(), "重建后的槽位是新容器");
        pool.release(replacement, true);
        pool.destroy();
    }

    @Test
    void ephemeralPoolCreatesAndDestroysEveryTime() {
        FakeFactory factory = new FakeFactory(tempDir);
        EphemeralSlotPool pool = new EphemeralSlotPool(factory);

        SandboxSlot slot = pool.borrow();
        pool.release(slot, true);

        assertTrue(((FakeSlot) slot).destroyed);
        assertEquals(1, factory.created.size(), "一次性池：借 1 次只创建 1 个槽位");
        assertEquals("ephemeral", pool.snapshot().get("mode"));
    }

    @Test
    void replenishCountsLiveSlotsNotIdleQueue() throws Exception {
        // 巡检把 idle 整体 drain 出来逐个 probe 的窗口里，槽位既不在 idle 也不在 inFlight。
        // 旧算法（capacity - idle.size() - inFlight）会在这里多建容器：真机实测 40 条任务后
        // 多出 pool-9 / pool-10，容器数超出 size。缺口必须按 live 算。
        FakeFactory factory = new FakeFactory(tempDir);
        SandboxPoolProperties properties = new SandboxPoolProperties();
        properties.setSize(2);
        properties.setBorrowTimeoutMs(5000L);
        properties.setReuseLimit(10);
        properties.setHealthCheckMs(1000L);
        ReusableSlotPool pool = new ReusableSlotPool(factory, properties);
        pool.afterPropertiesSet();
        for (FakeSlot slot : factory.created) {
            slot.probeDelayMs = 1500L;
        }

        // 等到巡检真的把 idle 抽空（此时两个槽位都在 probe 中）
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline && (int) pool.snapshot().get("idle") != 0) {
            Thread.sleep(20L);
        }
        assertEquals(0, pool.snapshot().get("idle"), "巡检期间 idle 应被抽空");

        // 同一时刻补建：不能多建
        pool.replenish();
        assertEquals(2, factory.created.size(), "巡检期间补建不能超过 size");
        assertEquals(2, pool.snapshot().get("live"));
        pool.destroy();
    }

    private ReusableSlotPool newPool(FakeFactory factory, int size, long borrowTimeoutMs, int reuseLimit) {
        SandboxPoolProperties properties = new SandboxPoolProperties();
        properties.setSize(size);
        properties.setBorrowTimeoutMs(borrowTimeoutMs);
        properties.setReuseLimit(reuseLimit);
        properties.setHealthCheckMs(60000L);
        return new ReusableSlotPool(factory, properties);
    }

    private SandboxSlot waitForBorrow(ReusableSlotPool pool, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return pool.borrow();
            } catch (SandboxBusyException e) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    static class FakeSlot implements SandboxSlot {
        private final String id;
        private final Path workspace;
        private final List<String> lifecycle;
        private int useCount;
        boolean healthy = true;
        /** 深度探针结果；healthy=true 而 probeHealthy=false 模拟「容器在跑但 /app 写不了」。 */
        boolean probeHealthy = true;
        boolean destroyed;
        long probeDelayMs;
        String containerId;

        FakeSlot(String id, Path workspace, List<String> lifecycle) {
            this.id = id;
            this.workspace = workspace;
            this.lifecycle = lifecycle;
            this.containerId = "container-" + id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Path workspace() {
            return workspace;
        }

        @Override
        public String containerId() {
            return containerId;
        }

        @Override
        public int useCount() {
            return useCount;
        }

        @Override
        public void markUsed() {
            useCount++;
        }

        @Override
        public boolean healthy() {
            return healthy && !destroyed;
        }

        @Override
        public boolean probe() {
            if (probeDelayMs > 0) {
                try {
                    Thread.sleep(probeDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return probeHealthy && healthy();
        }

        @Override
        public void reset() {
            lifecycle.add("reset:" + id);
        }

        @Override
        public void destroy() {
            destroyed = true;
            lifecycle.add("destroy:" + id);
        }
    }

    static class FakeFactory implements SandboxSlotFactory {
        private final Path root;
        final List<FakeSlot> created = new CopyOnWriteArrayList<>();
        final List<String> lifecycle = new CopyOnWriteArrayList<>();

        FakeFactory(Path root) {
            this.root = root;
        }

        @Override
        public SandboxSlot create(String slotId) {
            try {
                Files.createDirectories(root.resolve(slotId));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            FakeSlot slot = new FakeSlot(slotId, root.resolve(slotId), lifecycle);
            created.add(slot);
            return slot;
        }

        @Override
        public String backend() {
            return "native";
        }
    }
}
