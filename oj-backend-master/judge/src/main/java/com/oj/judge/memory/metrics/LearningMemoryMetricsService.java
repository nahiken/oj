package com.oj.judge.memory.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEventMapper;
import com.oj.judge.memory.mapper.LearningPatternMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.TypeCount;
import com.oj.judge.metrics.JudgeMetrics;

/**
 * 记忆链路指标（契约 0004 §7）：DB 口径 + 进程内计数器一起给。
 *
 * <p>为什么两者都要：DB 计数跨重启、跨实例可复查；`dropped` / `rate_limited` / `published`
 * 这类「没写进库」的事件只能看进程内计数器，否则排障时看不见丢弃。
 */
@Service
public class LearningMemoryMetricsService {

    private static final int DELAY_SAMPLE_LIMIT = 200;

    /** 契约 §7 点名要给的计数器：名字保持全限定，避免和判题计数器混淆。 */
    private static final String[] COUNTER_NAMES = {
            "memory.event.accepted",
            "memory.event.published",
            "memory.event.dropped",
            "memory.event.rate_limited",
            "memory.event.duplicate",
            "memory.event.publish_failed",
            "memory.event.abandoned",
            "memory.episode.built",
            "memory.episode.fast_path",
            "memory.episode.truncated",
            "memory.pattern.expired"
    };

    @Resource
    private LearningEventMapper eventMapper;
    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private LearningPatternMapper patternMapper;
    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private JudgeMetrics metrics;

    public Map<String, Object> snapshot() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", properties.isEnabled());
        view.put("transport", properties.getTransport());
        view.put("episodeGapMs", properties.getEpisodeGapMs());

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (TypeCount row : eventMapper.countByType(new Date(System.currentTimeMillis() - 86400000L))) {
            byType.put(row.getLabel(), row.getTotal());
        }
        events.put("byType", byType);
        events.put("uncovered", eventMapper.countUncovered());
        events.put("total", eventMapper.countAll());
        view.put("events", events);

        Map<String, Object> episodes = new LinkedHashMap<>();
        episodes.put("count", episodeMapper.countAll());
        List<Long> delays = delays();
        episodes.put("sample", delays.size());
        episodes.put("delayP50Ms", percentile(delays, 50));
        episodes.put("delayP95Ms", percentile(delays, 95));
        episodes.put("delayMaxMs", delays.isEmpty() ? 0L : delays.get(delays.size() - 1));
        view.put("episodes", episodes);

        Map<String, Object> patterns = new LinkedHashMap<>();
        patterns.put("count", patternMapper.countAll());
        view.put("patterns", patterns);

        Map<String, Object> counters = new LinkedHashMap<>();
        for (String name : COUNTER_NAMES) {
            counters.put(name, metrics.counter(name));
        }
        view.put("counters", counters);
        return view;
    }

    /**
     * 延迟 = Episode 物化时间 - 会话结束时间（契约 §7）。
     */
    private List<Long> delays() {
        List<Long> delays = new ArrayList<>();
        for (LearningEpisodeDO episode : episodeMapper.selectRecentForDelay(DELAY_SAMPLE_LIMIT)) {
            if (episode.getEndedAt() == null || episode.getCreatedAt() == null) {
                continue;
            }
            delays.add(Math.max(0L, episode.getCreatedAt().getTime() - episode.getEndedAt().getTime()));
        }
        Collections.sort(delays);
        return delays;
    }

    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }
}
