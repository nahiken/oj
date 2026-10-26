package com.oj.judge.memory.pattern;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEventMapper;
import com.oj.judge.memory.mapper.LearningPatternMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningPatternDO;
import com.oj.judge.memory.model.TypeCount;
import com.oj.judge.memory.support.ProblemTags;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.model.entity.Problem;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Pattern 重算任务（契约 0004 §5）。
 *
 * <p>输入是最近 `pattern-window-days` 天的 Episode + wa 事件的状态分布 + 题目标签；
 * 输出是每用户最多三行（类型维度幂等，重算覆盖并 version+1）。本轮不再成立的类型会被删除，
 * 否则过期结论会一直挂在用户画像上（复审 P6）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PatternBuildJob {

    private static final int ACTIVE_USER_LIMIT = 200;

    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private LearningEventMapper eventMapper;
    @Resource
    private LearningPatternMapper patternMapper;
    @Resource
    private ProblemMapper problemMapper;
    @Resource
    private PatternExtractor extractor;
    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private Snowflake snowflake;
    @Resource
    private JudgeMetrics metrics;

    @Scheduled(initialDelayString = "${oj.memory.pattern-interval-ms:600000}",
            fixedDelayString = "${oj.memory.pattern-interval-ms:600000}")
    public void scheduled() {
        try {
            rebuildAll();
        } catch (Exception e) {
            metrics.count("memory.pattern.error");
            log.error("evt=memory.pattern.error", e);
        }
    }

    /**
     * @return 重算了几个用户
     */
    public int rebuildAll() {
        Date since = windowStart();
        List<Long> uids = episodeMapper.selectActiveUids(since, ACTIVE_USER_LIMIT);
        int rebuilt = 0;
        for (Long uid : uids) {
            if (rebuild(uid)) {
                rebuilt++;
            }
        }
        if (rebuilt > 0) {
            log.info("evt=memory.pattern.rebuilt users={}", rebuilt);
        }
        return rebuilt;
    }

    public boolean rebuild(Long uid) {
        Date since = windowStart();
        List<LearningEpisodeDO> episodes = episodeMapper.selectSince(uid, since,
                properties.getPatternMaxEpisodes());
        if (episodes.isEmpty()) {
            // 窗口内已无 Episode：旧结论整体失效
            int removed = patternMapper.deleteByUid(uid);
            if (removed > 0) {
                metrics.count("memory.pattern.expired");
            }
            return false;
        }
        Map<String, Long> waStatusCounts = new LinkedHashMap<>();
        for (TypeCount row : eventMapper.countWaStatus(uid, since)) {
            if (row.getLabel() != null && row.getTotal() != null) {
                waStatusCounts.put(row.getLabel(), row.getTotal());
            }
        }
        List<PatternExtractor.Draft> drafts = extractor.extract(episodes, loadTags(episodes), waStatusCounts);
        Date now = new Date();
        for (PatternExtractor.Draft draft : drafts) {
            LearningPatternDO pattern = new LearningPatternDO();
            pattern.setId(snowflake.nextId());
            pattern.setUid(uid);
            pattern.setPatternType(draft.getPatternType());
            pattern.setDescription(draft.getDescription());
            pattern.setEvidence(JSONUtil.toJsonStr(draft.getEvidence()));
            pattern.setConfidence(draft.getConfidence());
            pattern.setVersion(1);
            pattern.setCreatedAt(now);
            pattern.setUpdatedAt(now);
            patternMapper.upsert(pattern);
            metrics.count("memory.pattern.upserted");
            log.info("evt=memory.pattern.upsert uid={} type={} confidence={} description={}",
                    uid, draft.getPatternType(), draft.getConfidence(), draft.getDescription());
        }
        int expired = dropStale(uid, drafts);
        if (expired > 0) {
            metrics.count("memory.pattern.expired");
        }
        return !drafts.isEmpty();
    }

    /**
     * 本轮没产出的类型 = 不再成立（阈值没过或证据滑出窗口），删除。
     *
     * <p>只删「连续两轮都没被刷新」的行：一次临时失败不该让结论消失再重建（版本号会归 1）。
     */
    private int dropStale(Long uid, List<PatternExtractor.Draft> drafts) {
        long twoIntervals = Math.max(2000L, properties.getPatternIntervalMs() * 2L);
        Date olderThan = new Date(System.currentTimeMillis() - twoIntervals);
        if (drafts.isEmpty()) {
            // 契约 §5 只有一条过期规则：连续两个周期没被刷新才删。之前这里直接删，
            // 一次临时失败就会把结论清空、版本号归 1。
            return patternMapper.deleteStale(uid, olderThan);
        }
        List<String> types = drafts.stream()
                .map(PatternExtractor.Draft::getPatternType)
                .collect(Collectors.toList());
        return patternMapper.deleteTypesNotIn(uid, types, olderThan);
    }

    private Map<Long, List<String>> loadTags(List<LearningEpisodeDO> episodes) {
        List<Long> problemIds = new ArrayList<>();
        for (LearningEpisodeDO episode : episodes) {
            Long problemId = episode.getProblemId();
            if (problemId != null && problemId > 0 && !problemIds.contains(problemId)) {
                problemIds.add(problemId);
            }
        }
        Map<Long, List<String>> tags = new LinkedHashMap<>();
        if (problemIds.isEmpty()) {
            return tags;
        }
        try {
            List<Problem> problems = problemMapper.selectBatchIds(problemIds);
            for (Problem problem : problems) {
                List<String> parsed = ProblemTags.parse(problem.getTags());
                if (!parsed.isEmpty()) {
                    tags.put(problem.getId(), parsed);
                }
            }
        } catch (Exception e) {
            log.warn("evt=memory.pattern.load_tags_failed reason={}", e.getMessage());
        }
        return tags;
    }

    private Date windowStart() {
        return new Date(System.currentTimeMillis() - properties.getPatternWindowDays() * 86400000L);
    }
}
