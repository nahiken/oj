package com.oj.judge.search.sync;

import java.util.Date;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.search.SearchProperties;
import com.oj.judge.search.index.SearchIndexService;

import lombok.extern.slf4j.Slf4j;

/**
 * 水位兜底（契约 0005 §3.1 第二条通路）：扫 `update_time > watermark` 的题，补写索引。
 *
 * <p>它覆盖两种事件会丢的场景：进程重启期间的写入、异步消费失败。
 *
 * <p>这里**同步**调用 {@link ProblemIndexWriter}，而不是再发一次事件：只有拿到写入结果，
 * 水位才知道能不能前进。写入失败时把水位退到该行之前，下一轮重试——否则"派发成功但写 ES
 * 失败"会被已经走过去的水位永久吞掉。
 *
 * <p>单轮上限 {@code watermarkBatch}：满批时水位只推进到本轮最后一行，下一轮接着扫，
 * 不会跳过第 N+1 条。
 *
 * <p>查询走 {@code selectChangedIgnoreLogic}：逻辑删除会把 `is_delete=1` 的行过滤掉，
 * 而"下架"恰恰是最需要补投的一类变更。
 */
@Component
@Slf4j
public class ProblemIndexWatermarkJob {

    private final ProblemMapper problemMapper;
    private final ProblemIndexWriter writer;
    private final SearchIndexService indexService;
    private final SearchProperties properties;

    private volatile long watermark = Long.MIN_VALUE;

    public ProblemIndexWatermarkJob(ProblemMapper problemMapper, ProblemIndexWriter writer,
            SearchIndexService indexService, SearchProperties properties) {
        this.problemMapper = problemMapper;
        this.writer = writer;
        this.indexService = indexService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${oj.search.watermark-initial-delay-ms:15000}",
            fixedDelayString = "${oj.search.watermark-interval-ms:60000}")
    public void scan() {
        if (!properties.isEnabled() || !indexService.available()) {
            return;
        }
        try {
            long scanStart = System.currentTimeMillis();
            long since = watermark == Long.MIN_VALUE
                    ? scanStart - properties.getWatermarkLookbackMs()
                    : watermark - properties.getWatermarkOverlapMs();
            int batch = Math.max(1, properties.getWatermarkBatch());
            List<Problem> changed = problemMapper.selectChangedIgnoreLogic(new Date(since), batch);

            int written = 0;
            for (Problem problem : changed) {
                if (!writer.index(problem.getId(), "watermark")) {
                    log.warn("search watermark paused at id={}: index write failed", problem.getId());
                    break;
                }
                written++;
            }
            watermark = advanceWatermark(changed, written, batch, scanStart,
                    properties.getWatermarkOverlapMs());
            if (!changed.isEmpty()) {
                log.info("search watermark caught up: {} problem(s)", changed.size());
            }
        } catch (RuntimeException e) {
            // 兜底任务失败不能影响主链路，也不能让水位前进
            log.warn("search watermark scan failed: {}", e.toString());
        }
    }

    /**
     * 计算下一轮水位（纯函数，单测覆盖三条路径）：
     *
     * <ul>
     *   <li>有空行失败：停在失败行之前，下一轮重试；</li>
     *   <li>满批且全成功：只推进到最后一行，下一轮接着扫；</li>
     *   <li>未满批且全成功：推进到本轮开始时间。</li>
     * </ul>
     */
    static long advanceWatermark(List<Problem> changed, int written, int batch, long scanStart, long overlap) {
        if (changed.isEmpty()) {
            return scanStart;
        }
        int safe = Math.max(0, Math.min(written, changed.size()));
        if (safe < changed.size()) {
            Date failed = changed.get(safe).getUpdateTime();
            return failed == null ? scanStart : failed.getTime() - overlap;
        }
        if (changed.size() >= batch) {
            Date last = changed.get(changed.size() - 1).getUpdateTime();
            return last == null ? scanStart : last.getTime();
        }
        return scanStart;
    }
}
