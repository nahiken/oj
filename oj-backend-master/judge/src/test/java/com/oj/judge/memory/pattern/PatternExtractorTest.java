package com.oj.judge.memory.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningPatternDO;

/**
 * 规则归纳必须可复查：置信度、描述与证据都能由输入手算出来。
 */
class PatternExtractorTest {

    private final PatternExtractor extractor = new PatternExtractor();

    @Test
    void dominantFailureBecomesErrorFocus() {
        Map<String, Long> wa = new LinkedHashMap<>();
        wa.put("3", 7L);
        wa.put("4", 2L);

        List<PatternExtractor.Draft> drafts = extractor.extract(new ArrayList<>(), new LinkedHashMap<>(), wa);

        assertEquals(1, drafts.size());
        PatternExtractor.Draft draft = drafts.get(0);
        assertEquals(LearningPatternDO.TYPE_ERROR_FOCUS, draft.getPatternType());
        assertTrue(draft.getDescription().contains("超出时间限制"),
                "3 = TIMEOUT，描述里必须出现中文标签: " + draft.getDescription());
        assertTrue(draft.getDescription().contains("占 78%（7/9）"), draft.getDescription());
        assertEquals(new BigDecimal("0.778"), draft.getConfidence());
        assertEquals(9L, ((Number) draft.getEvidence().get("samples")).longValue());
    }

    @Test
    void tooFewFailuresYieldsNoConclusion() {
        Map<String, Long> wa = new LinkedHashMap<>();
        wa.put("3", 2L);

        assertTrue(extractor.extract(new ArrayList<>(), new LinkedHashMap<>(), wa).isEmpty());
    }

    @Test
    void weakestTagIsReportedWithFailRate() {
        List<LearningEpisodeDO> episodes = Arrays.asList(
                episode(1L, "WA", 2),
                episode(1L, "WA", 1),
                episode(2L, "AC", 1));
        Map<Long, List<String>> tags = new LinkedHashMap<>();
        tags.put(1L, List.of("数组"));
        tags.put(2L, List.of("数组"));

        List<PatternExtractor.Draft> drafts = extractor.extract(episodes, tags, new LinkedHashMap<>());

        // 同一批 Episode 也会产出 ATTEMPT_STYLE，这里只断言 WEAK_TAG
        PatternExtractor.Draft draft = drafts.stream()
                .filter(item -> LearningPatternDO.TYPE_WEAK_TAG.equals(item.getPatternType()))
                .findFirst()
                .orElseThrow();
        // 标签「数组」共 4 次提交、1 次通过 → 失败率 0.75
        assertEquals(new BigDecimal("0.750"), draft.getConfidence());
        assertTrue(draft.getDescription().contains("「数组」"), draft.getDescription());
        assertTrue(draft.getDescription().contains("失败率 75%"), draft.getDescription());
    }

    @Test
    void weakTagCountsEveryAcceptedAttemptNotJustTheLastOne() {
        // 3 次提交、2 次 AC：失败率应是 1/3，而不是「最后一次是 AC 就算 0 次失败」。
        LearningEpisodeDO multiAc = episode(1L, "AC", 3);
        multiAc.setAcCount(2);
        Map<Long, List<String>> tags = new LinkedHashMap<>();
        tags.put(1L, List.of("数组"));

        List<PatternExtractor.Draft> drafts =
                extractor.extract(List.of(multiAc), tags, new LinkedHashMap<>());

        PatternExtractor.Draft draft = drafts.stream()
                .filter(item -> LearningPatternDO.TYPE_WEAK_TAG.equals(item.getPatternType()))
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("0.333"), draft.getConfidence());
        assertEquals(2L, ((Number) draft.getEvidence().get("acceptedEpisodes")).longValue());
    }

    @Test
    void tagBelowSampleThresholdIsIgnored() {
        List<LearningEpisodeDO> episodes = Arrays.asList(episode(1L, "WA", 1), episode(1L, "WA", 1));
        Map<Long, List<String>> tags = Map.of(1L, List.of("数组"));

        assertTrue(extractor.extract(episodes, tags, new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void attemptStyleAveragesAcceptedSessions() {
        List<LearningEpisodeDO> episodes = Arrays.asList(
                episode(1L, "AC", 2),
                episode(2L, "AC", 4),
                episode(3L, "WA", 9));

        List<PatternExtractor.Draft> drafts = extractor.extract(episodes, new LinkedHashMap<>(), new LinkedHashMap<>());

        assertEquals(1, drafts.size());
        PatternExtractor.Draft draft = drafts.get(0);
        assertEquals(LearningPatternDO.TYPE_ATTEMPT_STYLE, draft.getPatternType());
        assertTrue(draft.getDescription().contains("平均提交 3.00 次"), draft.getDescription());
        // 契约 0004 §5：min(1, 1/avg)，avg=3 → 0.333（不是「AC 会话数/10」）
        assertEquals(new BigDecimal("0.333"), draft.getConfidence());
    }

    @Test
    void attemptStyleConfidenceFallsAsAverageAttemptsRises() {
        // avg=2 -> 1/2 = 0.500; avg=4 -> 1/4 = 0.250（次数越多越确定「靠试」）
        assertEquals(new BigDecimal("0.500"), attemptStyleConfidence(2));
        assertEquals(new BigDecimal("0.250"), attemptStyleConfidence(4));
    }

    private BigDecimal attemptStyleConfidence(int attempts) {
        List<LearningEpisodeDO> episodes = List.of(episode(1L, "AC", attempts));
        return extractor.extract(episodes, new LinkedHashMap<>(), new LinkedHashMap<>()).stream()
                .filter(item -> LearningPatternDO.TYPE_ATTEMPT_STYLE.equals(item.getPatternType()))
                .findFirst()
                .orElseThrow()
                .getConfidence();
    }

    @Test
    void sameInputProducesSameDrafts() {
        List<LearningEpisodeDO> episodes = Arrays.asList(episode(1L, "AC", 3), episode(1L, "WA", 2));
        Map<Long, List<String>> tags = Map.of(1L, List.of("数组"));
        Map<String, Long> wa = new LinkedHashMap<>();
        wa.put("4", 3L);

        List<PatternExtractor.Draft> first = extractor.extract(episodes, tags, wa);
        List<PatternExtractor.Draft> second = extractor.extract(episodes, tags, wa);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getPatternType(), second.get(i).getPatternType());
            assertEquals(first.get(i).getDescription(), second.get(i).getDescription());
            assertEquals(first.get(i).getConfidence(), second.get(i).getConfidence());
        }
    }

    private LearningEpisodeDO episode(Long problemId, String outcome, int attempts) {
        LearningEpisodeDO episode = new LearningEpisodeDO();
        episode.setProblemId(problemId);
        episode.setOutcome(outcome);
        episode.setAttemptCount(attempts);
        // 契约 §4.2：通过数来自 ac_count，不是 outcome 标志位。
        episode.setAcCount("AC".equals(outcome) ? 1 : 0);
        return episode;
    }
}
