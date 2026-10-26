package com.oj.judge.memory.pattern;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningPatternDO;
import com.oj.judge.memory.support.JudgeStatusLabel;

import lombok.Data;

/**
 * 规则归纳（契约 0004 §5）：Episode + 失败状态分布 + 题目标签 → 三类 Pattern。
 *
 * <p>纯计算，不碰 IO：同一输入两次调用结果一致（含排序与置信度舍入），便于单测与复查。
 * LLM 归纳留给 P6，这里只做「可被证据支撑的统计结论」。
 */
@Component
public class PatternExtractor {

    /** 样本下限：太少不给结论，避免把偶然当画像 */
    private static final int MIN_FAIL_SAMPLES = 3;
    private static final int MIN_TAG_SUBMITS = 3;

    /**
     * @param episodes       窗口内的 Episode（已按 started_at 倒序或正序均可）
     * @param tagsByProblem  problemId → 标签列表
     * @param waStatusCounts judgeStatus（字符串）→ 次数，来自 wa 事件
     */
    public List<Draft> extract(List<LearningEpisodeDO> episodes, Map<Long, List<String>> tagsByProblem,
            Map<String, Long> waStatusCounts) {
        List<Draft> drafts = new ArrayList<>();
        errorFocus(waStatusCounts).ifPresent(drafts::add);
        weakTag(episodes, tagsByProblem).ifPresent(drafts::add);
        attemptStyle(episodes).ifPresent(drafts::add);
        return drafts;
    }

    private Optional<Draft> errorFocus(Map<String, Long> waStatusCounts) {
        long total = waStatusCounts.values().stream().mapToLong(Long::longValue).sum();
        if (total < MIN_FAIL_SAMPLES) {
            return Optional.empty();
        }
        Map.Entry<String, Long> top = waStatusCounts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .orElse(null);
        if (top == null) {
            return Optional.empty();
        }
        double share = top.getValue() / (double) total;
        if (share < 0.5) {
            return Optional.empty();
        }
        String label = JudgeStatusLabel.of(top.getKey());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("samples", total);
        evidence.put("distribution", new LinkedHashMap<>(waStatusCounts));
        Draft draft = new Draft();
        draft.setPatternType(LearningPatternDO.TYPE_ERROR_FOCUS);
        draft.setDescription(String.format("最近失败中 %s 占 %.0f%%（%d/%d），是当前主要卡点",
                label, share * 100, top.getValue(), total));
        draft.setEvidence(evidence);
        draft.setConfidence(confidence(share));
        return Optional.of(draft);
    }

    private Optional<Draft> weakTag(List<LearningEpisodeDO> episodes,
            Map<Long, List<String>> tagsByProblem) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        for (LearningEpisodeDO episode : episodes) {
            List<String> tags = tagsByProblem.get(episode.getProblemId());
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            int attempts = episode.getAttemptCount() == null ? 0 : episode.getAttemptCount();
            if (attempts <= 0) {
                continue;
            }
            // 契约 §4.2：用 ac_count，不能用「最后一次是否 AC」代替。一道题多次 AC
            // 若只记 1，失败率会被抬高、置信度跟着虚高。老数据 ac_count 为空时按 0。
            int acCount = episode.getAcCount() == null ? 0 : Math.min(episode.getAcCount(), attempts);
            for (String tag : tags) {
                long[] row = stats.computeIfAbsent(tag, key -> new long[2]);
                row[0] += attempts;
                row[1] += acCount;
            }
        }
        Map.Entry<String, long[]> worst = null;
        double worstRate = -1;
        for (Map.Entry<String, long[]> entry : stats.entrySet()) {
            long submits = entry.getValue()[0];
            long acs = entry.getValue()[1];
            if (submits < MIN_TAG_SUBMITS) {
                continue;
            }
            double failRate = 1.0 - acs / (double) submits;
            // 同失败率时优先样本量大的，保证结果稳定
            if (failRate > worstRate + 1e-9
                    || (Math.abs(failRate - worstRate) <= 1e-9 && worst != null
                            && submits > worst.getValue()[0])) {
                worst = entry;
                worstRate = failRate;
            }
        }
        if (worst == null) {
            return Optional.empty();
        }
        long submits = worst.getValue()[0];
        long acs = worst.getValue()[1];
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("tag", worst.getKey());
        evidence.put("submits", submits);
        evidence.put("acceptedEpisodes", acs);
        evidence.put("failRate", confidence(worstRate));
        Draft draft = new Draft();
        draft.setPatternType(LearningPatternDO.TYPE_WEAK_TAG);
        draft.setDescription(String.format("「%s」类题目提交 %d 次、通过 %d 次，失败率 %.0f%%，是当前薄弱标签",
                worst.getKey(), submits, acs, worstRate * 100));
        draft.setEvidence(evidence);
        draft.setConfidence(confidence(worstRate));
        return Optional.of(draft);
    }

    private Optional<Draft> attemptStyle(List<LearningEpisodeDO> episodes) {
        List<LearningEpisodeDO> solved = new ArrayList<>();
        for (LearningEpisodeDO episode : episodes) {
            if (LearningEpisodeDO.OUTCOME_AC.equals(episode.getOutcome())
                    && episode.getAttemptCount() != null && episode.getAttemptCount() > 0) {
                solved.add(episode);
            }
        }
        if (solved.isEmpty()) {
            return Optional.empty();
        }
        long totalAttempts = solved.stream().mapToLong(LearningEpisodeDO::getAttemptCount).sum();
        double average = totalAttempts / (double) solved.size();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("acEpisodes", solved.size());
        evidence.put("totalAttempts", totalAttempts);
        evidence.put("avgAttempts", BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP));
        Draft draft = new Draft();
        draft.setPatternType(LearningPatternDO.TYPE_ATTEMPT_STYLE);
        draft.setDescription(String.format("通过一道题平均提交 %.2f 次（%d 道已通过题目）", average, solved.size()));
        draft.setEvidence(evidence);
        // 契约 0004 §5：min(1, 1/avg)——平均次数越多，越能确定是「靠试」而不是「一次想清楚」
        draft.setConfidence(confidence(Math.min(1.0, 1.0 / average)));
        return Optional.of(draft);
    }

    private BigDecimal confidence(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 归纳结果：还没分配 id/uid/version 的 Pattern 草案。
     */
    @Data
    public static class Draft {
        private String patternType;
        private String description;
        private Map<String, Object> evidence;
        private BigDecimal confidence;
    }
}
