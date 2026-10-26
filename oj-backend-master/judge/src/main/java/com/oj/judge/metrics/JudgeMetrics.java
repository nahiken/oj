package com.oj.judge.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 判题链路指标：计数 + 阶段耗时。JSON 由 {@code GET /judge/metrics} 直接读。
 *
 * <p>不引入 Micrometer/Actuator：离线仓库缺依赖，且当前只需要压测脚本可读的稳定字段。
 * 后续接 Prometheus 时把本类换成 MeterRegistry 实现即可（已记技术债）。
 */
@Component
public class JudgeMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stageSum = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stageCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stageMax = new ConcurrentHashMap<>();

    public void count(String name) {
        counters.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
    }

    public void add(String name, long value) {
        counters.computeIfAbsent(name, key -> new AtomicLong()).addAndGet(value);
    }

    public void recordStage(String stage, long ms) {
        long value = Math.max(0L, ms);
        stageSum.computeIfAbsent(stage, key -> new AtomicLong()).addAndGet(value);
        stageCount.computeIfAbsent(stage, key -> new AtomicLong()).incrementAndGet();
        stageMax.computeIfAbsent(stage, key -> new AtomicLong()).accumulateAndGet(value, Math::max);
    }

    public long counter(String name) {
        AtomicLong value = counters.get(name);
        return value == null ? 0L : value.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> counterView = new LinkedHashMap<>();
        counters.forEach((name, value) -> counterView.put(name, value.get()));
        map.put("counters", counterView);

        Map<String, Object> stageView = new LinkedHashMap<>();
        for (String stage : stageCount.keySet()) {
            long count = stageCount.get(stage).get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", count);
            item.put("avgMs", count == 0 ? 0 : stageSum.get(stage).get() / count);
            item.put("maxMs", stageMax.get(stage).get());
            stageView.put(stage, item);
        }
        map.put("stages", stageView);
        return map;
    }
}
