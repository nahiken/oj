package com.oj.codesandbox.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次沙箱执行的阶段计时器。
 *
 * <p>非线程安全：每次 executeCode 新建一个，只在该请求的线程里用。
 * 结果写进 {@code ExecuteCodeRes.stageMs}，judge 侧原样记日志，不额外打点。
 */
public class StageTimer {

    private final long startNanos = System.nanoTime();
    private final Map<String, Long> stages = new LinkedHashMap<>();

    public long mark() {
        return System.nanoTime();
    }

    public void record(String stage, long markNanos) {
        long ms = (System.nanoTime() - markNanos) / 1_000_000L;
        stages.merge(stage, ms, Long::sum);
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>(stages);
        copy.put("total", elapsedMs());
        return copy;
    }
}
