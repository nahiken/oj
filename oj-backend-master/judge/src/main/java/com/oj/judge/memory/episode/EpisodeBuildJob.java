package com.oj.judge.memory.episode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.event.LearningEventStoredEvent;
import com.oj.judge.memory.event.LearningEventType;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEventMapper;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningEventDO;
import com.oj.judge.memory.model.SessionKey;
import com.oj.judge.memory.support.JudgeStatusLabel;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Episode 物化任务（契约 0004 §4）。
 *
 * <p>两条触发路径：
 * <ul>
 *   <li>定时扫「有未消费事件、且最后事件早于 now-gap」的分组 → 物化已关闭会话（放弃/探索型会话）</li>
 *   <li>会话结束快速通道：`ac` / `dwell` 落库后（`LearningEventStoredEvent`）立即物化该题会话</li>
 * </ul>
 *
 * <p>幂等靠三层：唯一键 `(uid, problem_id, started_at)`、`ON DUPLICATE KEY UPDATE id=id`、
 * 回填 `episode_id` 只更新仍为 NULL 的行。多实例同时跑不会产生第二份 Episode。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EpisodeBuildJob {

    /** 分页读取未消费事件的页大小：会话再大也不会被一次查询截断（复审 P9b） */
    private static final int PAGE_SIZE = 500;

    /** 一轮最多放弃多少条超出回看窗口的事件，避免一次 UPDATE 锁太多行 */
    private static final int ABANDON_LIMIT = 5000;

    @Resource
    private LearningEventMapper eventMapper;
    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private EpisodeSummarizer summarizer;
    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private JudgeMetrics metrics;
    @Resource
    private Snowflake snowflake;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(initialDelayString = "${oj.memory.episode-interval-ms:60000}",
            fixedDelayString = "${oj.memory.episode-interval-ms:60000}")
    public void scheduled() {
        try {
            buildOnce();
        } catch (Exception e) {
            metrics.count("memory.episode.error");
            log.error("evt=memory.episode.error", e);
        }
    }

    /**
     * 扫一轮：处理所有「最后事件已静默超过 gap」的分组，并放弃回看窗口外的滞留事件。
     *
     * @return 本轮新物化的 Episode 数（重复跑返回 0）
     */
    public int buildOnce() {
        if (!acquireJobLock()) {
            metrics.count("memory.episode.skipped_locked");
            return 0;
        }
        Date now = new Date();
        Date closedBefore = new Date(now.getTime() - properties.getEpisodeGapMs());
        Date after = new Date(now.getTime() - properties.getLookbackDays() * 86400000L);
        abandonOutOfWindow(after);
        List<SessionKey> keys = eventMapper.selectUncoveredKeys(after, closedBefore, properties.getBatchKeys());
        int built = 0;
        for (SessionKey key : keys) {
            built += buildKey(key.getUid(), key.getProblemId(), closedBefore);
        }
        if (!keys.isEmpty()) {
            log.info("evt=memory.episode.sweep groups={} built={}", keys.size(), built);
        }
        return built;
    }

    /**
     * 回看窗口外的未消费事件永远不会再被扫描到：显式标记为放弃（`episode_id=0`），
     * 让 `events.uncovered` 能归零，而不是无限滞留（复审 P9a）。
     */
    private void abandonOutOfWindow(Date before) {
        try {
            int abandoned = eventMapper.abandonBefore(before, ABANDON_LIMIT);
            if (abandoned > 0) {
                metrics.add("memory.event.abandoned", abandoned);
                log.warn("evt=memory.event.abandoned rows={} before={}", abandoned, before);
            }
        } catch (Exception e) {
            metrics.count("memory.event.abandon_failed");
            log.warn("evt=memory.event.abandon_failed reason={}", e.getMessage());
        }
    }

    /**
     * 快速通道：`ac` 或 `dwell`（离开页面）落库后立刻关闭该 (uid, problem) 的当前会话。
     *
     * <p>为什么两种都要：只靠 AC 的话，没做出来的会话要等 30 分钟静默才可读；而 `dwell`
     * 本来就是「用户离开这道题」的信号，语义上等价于会话结束。
     */
    @EventListener
    public void onEventStored(LearningEventStoredEvent event) {
        if (!properties.isEnabled() || !properties.isAcFastPath() || event.getUid() == null) {
            return;
        }
        onSessionEnd(event.getUid(), event.getProblemId());
    }

    public int onSessionEnd(Long uid, Long problemId) {
        if (!properties.isAcFastPath() || uid == null) {
            return 0;
        }
        try {
            int built = buildKey(uid, problemId == null ? 0L : problemId, new Date());
            if (built > 0) {
                metrics.count("memory.episode.fast_path");
            }
            return built;
        } catch (Exception e) {
            metrics.count("memory.episode.error");
            log.warn("evt=memory.episode.fast_path_failed uid={} problemId={} reason={}",
                    uid, problemId, e.getMessage());
            return 0;
        }
    }

    /**
     * 物化某个分组里所有「在 closedBefore 之前结束」的会话。
     */
    public int buildKey(Long uid, Long problemId, Date closedBefore) {
        List<LearningEventDO> fetched = loadUncovered(uid, problemId);
        if (fetched.isEmpty()) {
            return 0;
        }
        int cap = Math.max(1, properties.getMaxEventsPerSession());
        // 多读一条来判断「是否真的超上限」：正好等于上限的会话不该被标成截断
        boolean capped = fetched.size() > cap;
        List<LearningEventDO> events = capped ? new ArrayList<>(fetched.subList(0, cap)) : fetched;
        List<SessionSplitter.Session> sessions = SessionSplitter.split(events, properties.getEpisodeGapMs());
        if (capped) {
            // 没读完就分页结束：最后一段可能是「半个会话」，显式标注并计数，不静默切成两份
            metrics.count("memory.episode.truncated");
            log.warn("evt=memory.episode.truncated uid={} problemId={} events={} cap={}",
                    uid, problemId, events.size(), properties.getMaxEventsPerSession());
        }
        int built = 0;
        for (int i = 0; i < sessions.size(); i++) {
            SessionSplitter.Session session = sessions.get(i);
            if (session.endedAt().after(closedBefore)) {
                continue;
            }
            // 契约 §4.5：截断是按会话判断的。整组被截断不代表每个会话都被截断——
            // 「两个各 300 条的会话、cap=500」里，尾部 200 条是完整会话，不该标截断。
            boolean truncatedTail = capped && i == sessions.size() - 1 && session.size() >= cap;
            if (materialize(uid, problemId, session, truncatedTail)) {
                built++;
            }
        }
        return built;
    }

    /**
     * 分页把该分组所有未消费事件读出来：先切会话再判断上限，避免「读到 500 条就切一刀」
     * 把一个大会话伪装成两个 Episode。
     */
    private List<LearningEventDO> loadUncovered(Long uid, Long problemId) {
        int cap = Math.max(1, properties.getMaxEventsPerSession());
        int probe = cap + 1;
        List<LearningEventDO> events = new ArrayList<>();
        Date cursorTime = new Date(0L);
        long cursorId = 0L;
        while (events.size() < probe) {
            int pageSize = Math.min(PAGE_SIZE, probe - events.size());
            List<LearningEventDO> page = eventMapper.selectUncoveredPage(uid, problemId, cursorTime, cursorId,
                    pageSize);
            if (page.isEmpty()) {
                break;
            }
            events.addAll(page);
            if (page.size() < pageSize) {
                break;
            }
            LearningEventDO last = page.get(page.size() - 1);
            cursorTime = last.getOccurredAt();
            cursorId = last.getId();
        }
        return events;
    }

    private boolean materialize(Long uid, Long problemId, SessionSplitter.Session session, boolean truncated) {
        LearningEpisodeDO episode = assemble(uid, problemId, session, truncated);
        episodeMapper.insertIgnore(episode);
        LearningEpisodeDO stored = episodeMapper.selectByKey(uid, problemId, episode.getStartedAt());
        if (stored == null) {
            metrics.count("memory.episode.missing_after_insert");
            log.error("evt=memory.episode.missing uid={} problemId={} startedAt={}", uid, problemId,
                    episode.getStartedAt());
            return false;
        }
        List<Long> ids = session.events().stream().map(LearningEventDO::getId).collect(Collectors.toList());
        eventMapper.markEpisode(ids, stored.getId());
        metrics.count("memory.episode.built");
        metrics.recordStage("memory.episode.delay",
                Math.max(0L, System.currentTimeMillis() - episode.getEndedAt().getTime()));
        log.info("evt=memory.episode.materialized uid={} problemId={} episodeId={} outcome={} events={} attempts={}",
                uid, problemId, stored.getId(), episode.getOutcome(), session.size(),
                episode.getAttemptCount());
        return true;
    }

    private LearningEpisodeDO assemble(Long uid, Long problemId, SessionSplitter.Session session,
            boolean truncated) {
        int submits = 0;
        int runs = 0;
        int solutionViews = 0;
        int copies = 0;
        int ac = 0;
        int wa = 0;
        long dwellMs = 0L;
        String firstFail = null;

        for (LearningEventDO event : session.events()) {
            LearningEventType type = LearningEventType.fromValue(event.getEventType());
            if (type == null) {
                continue;
            }
            switch (type) {
                case SUBMIT:
                    submits++;
                    break;
                case RUN:
                    runs++;
                    break;
                case VIEW_SOLUTION:
                    solutionViews++;
                    break;
                case COPY_CODE:
                    copies++;
                    break;
                case DWELL:
                    dwellMs += longPayload(event.getPayload(), "durationMs");
                    break;
                case AC:
                    ac++;
                    break;
                case WA:
                    wa++;
                    if (firstFail == null) {
                        firstFail = failLabel(event.getPayload());
                    }
                    break;
                default:
                    break;
            }
        }

        LearningEpisodeDO episode = new LearningEpisodeDO();
        episode.setId(snowflake.nextId());
        episode.setUid(uid);
        episode.setProblemId(problemId == null ? 0L : problemId);
        episode.setStartedAt(session.startedAt());
        episode.setEndedAt(session.endedAt());
        episode.setOutcome(ac > 0 ? LearningEpisodeDO.OUTCOME_AC
                : wa > 0 ? LearningEpisodeDO.OUTCOME_WA : LearningEpisodeDO.OUTCOME_EXPLORE);
        episode.setAttemptCount(submits);
        episode.setAcCount(ac);
        episode.setRunCount(runs);
        episode.setSolutionViews(solutionViews);
        episode.setCopyCount(copies);
        episode.setDwellMs(dwellMs);
        episode.setEventCount(session.size());
        episode.setTruncated(truncated);
        episode.setSummarySource(EpisodeSummarizer.SOURCE_RULE);
        episode.setSummaryVersion(EpisodeSummarizer.VERSION);
        episode.setCreatedAt(new Date());
        episode.setSummary(summarizer.summarize(episode, firstFail));
        return episode;
    }

    private long longPayload(String json, String field) {
        if (json == null || json.isBlank()) {
            return 0L;
        }
        try {
            JSONObject object = JSONUtil.parseObj(json);
            return object.getLong(field, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String failLabel(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JudgeStatusLabel.of(JSONUtil.parseObj(json).getInt("judgeStatus"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 多实例互斥：抢不到就跳过本轮。
     *
     * <p>TTL 与调度周期同源（`episode-interval-ms` 的 90%，夹在 5s–30s）：固定 55s 会把 10s 周期
     * 实际拖成 55s 一次（P4 实测 37s 才物化）。不主动释放：崩溃后最多等一个 TTL，幂等兜住重复。
     */
    private boolean acquireJobLock() {
        try {
            long intervalMs = Math.max(1000L, properties.getEpisodeIntervalMs());
            long ttlMs = Math.max(5000L, Math.min((long) (intervalMs * 0.9), 30000L));
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent("mem:job:episode",
                    String.valueOf(System.currentTimeMillis()), Duration.ofMillis(ttlMs));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 不可用时不能让整个记忆链路停摆：放行，幂等仍能兜住重复
            return true;
        }
    }
}
