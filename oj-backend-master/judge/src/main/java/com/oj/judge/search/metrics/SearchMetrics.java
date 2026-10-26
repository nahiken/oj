package com.oj.judge.search.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.oj.judge.search.SearchProperties;

/**
 * 检索计数器（契约 0005 §6）。进程内计数，与判题计数器同一形态：不引 Micrometer。
 */
@Component
public class SearchMetrics {

    private final SearchProperties properties;

    private final AtomicLong indexOk = new AtomicLong();
    private final AtomicLong indexFailed = new AtomicLong();
    private final AtomicLong indexConflict = new AtomicLong();
    private final AtomicLong queryBm25 = new AtomicLong();
    private final AtomicLong queryDense = new AtomicLong();
    private final AtomicLong queryHybrid = new AtomicLong();
    private final AtomicLong degraded = new AtomicLong();

    /** 最近一次查询的服务端耗时：ES 往返 vs 总耗时（用于归因） */
    private volatile long lastEsMs;
    private volatile long lastTotalMs;

    public SearchMetrics(SearchProperties properties) {
        this.properties = properties;
    }

    public void indexOk(long n) {
        indexOk.addAndGet(n);
    }

    public void indexFailed(long n) {
        indexFailed.addAndGet(n);
    }

    public void indexConflict(long n) {
        indexConflict.addAndGet(n);
    }

    public void bm25(long esMs, long totalMs) {
        queryBm25.incrementAndGet();
        this.lastEsMs = esMs;
        this.lastTotalMs = totalMs;
    }

    public void dense(long esMs, long totalMs) {
        queryDense.incrementAndGet();
        this.lastEsMs = esMs;
        this.lastTotalMs = totalMs;
    }

    public void hybrid(long esMs, long totalMs) {
        queryHybrid.incrementAndGet();
        this.lastEsMs = esMs;
        this.lastTotalMs = totalMs;
    }

    public void degraded() {
        degraded.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("index", properties.getIndexName());
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("search.index.ok", indexOk.get());
        counters.put("search.index.failed", indexFailed.get());
        counters.put("search.index.conflict", indexConflict.get());
        counters.put("search.query.bm25", queryBm25.get());
        counters.put("search.query.dense", queryDense.get());
        counters.put("search.query.hybrid", queryHybrid.get());
        counters.put("search.degraded", degraded.get());
        out.put("counters", counters);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("search.es_ms", lastEsMs);
        latency.put("search.total_ms", lastTotalMs);
        out.put("latency", latency);
        return out;
    }
}
