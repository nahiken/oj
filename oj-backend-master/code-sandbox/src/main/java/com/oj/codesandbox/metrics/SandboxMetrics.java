package com.oj.codesandbox.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 沙箱侧指标：执行次数、繁忙拒绝、各阶段耗时（次数/均值/最大值）。
 *
 * <p>不引入 Micrometer，避免离线仓库缺依赖；JSON 由 {@code GET /sandbox/metrics} 直接读。
 */
@Component
public class SandboxMetrics {

    private final Map<String, AtomicLong> stageSum = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stageCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stageMax = new ConcurrentHashMap<>();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong busyRejections = new AtomicLong();

    public void recordStages(Map<String, Long> stageMs) {
        if (stageMs == null || stageMs.isEmpty()) {
            return;
        }
        executions.incrementAndGet();
        stageMs.forEach((stage, ms) -> {
            long value = ms == null ? 0L : ms;
            stageSum.computeIfAbsent(stage, key -> new AtomicLong()).addAndGet(value);
            stageCount.computeIfAbsent(stage, key -> new AtomicLong()).incrementAndGet();
            stageMax.computeIfAbsent(stage, key -> new AtomicLong()).accumulateAndGet(value, Math::max);
        });
    }

    public void recordBusy() {
        busyRejections.incrementAndGet();
    }

    public Map<String, Object> snapshot(Map<String, Object> poolSnapshot) {
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String stage : stageCount.keySet()) {
            long count = stageCount.get(stage).get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", count);
            item.put("avgMs", count == 0 ? 0 : stageSum.get(stage).get() / count);
            item.put("maxMs", stageMax.get(stage).get());
            // 累计和：按「快照前后相减」算单轮区间耗时，avg*count 的反推会带整除去尾误差
            item.put("sumMs", stageSum.get(stage).get());
            stages.put(stage, item);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pool", poolSnapshot);
        map.put("executions", executions.get());
        map.put("busyRejections", busyRejections.get());
        map.put("stages", stages);
        return map;
    }
}
