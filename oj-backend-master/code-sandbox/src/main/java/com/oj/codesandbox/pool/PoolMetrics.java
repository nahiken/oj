package com.oj.codesandbox.pool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 池计数：借约次数/等待/超时、创建、复用、销毁。快照直接进 /metrics JSON。
 */
public class PoolMetrics {

    private final AtomicLong borrowCount = new AtomicLong();
    private final AtomicLong borrowTimeouts = new AtomicLong();
    private final AtomicLong borrowTotalMs = new AtomicLong();
    private final AtomicLong borrowMaxMs = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong doubleRelease = new AtomicLong();

    public void recordBorrow(long waitMs) {
        borrowCount.incrementAndGet();
        borrowTotalMs.addAndGet(waitMs);
        borrowMaxMs.accumulateAndGet(waitMs, Math::max);
    }

    public void recordTimeout() {
        borrowTimeouts.incrementAndGet();
    }

    public void recordCreated() {
        created.incrementAndGet();
    }

    public void recordReused() {
        reused.incrementAndGet();
    }

    public void recordDiscarded() {
        discarded.incrementAndGet();
    }

    /** 重复归还：调用方 bug，计数用于发现问题而不是静默污染池。 */
    public void recordDoubleRelease() {
        doubleRelease.incrementAndGet();
    }

    public long borrowCount() {
        return borrowCount.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        long borrows = borrowCount.get();
        map.put("borrowCount", borrows);
        map.put("borrowTimeouts", borrowTimeouts.get());
        map.put("avgBorrowMs", borrows == 0 ? 0 : borrowTotalMs.get() / borrows);
        map.put("maxBorrowMs", borrowMaxMs.get());
        map.put("created", created.get());
        map.put("reused", reused.get());
        map.put("discarded", discarded.get());
        map.put("doubleRelease", doubleRelease.get());
        return map;
    }
}
