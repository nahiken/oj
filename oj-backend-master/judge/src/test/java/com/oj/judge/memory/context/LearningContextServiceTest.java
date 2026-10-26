package com.oj.judge.memory.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oj.judge.memory.vo.LearningContextVo;

/**
 * 预算裁剪：先丢 Episode，再丢 Pattern；题目块不裁。估算值必须随内容单调。
 */
class LearningContextServiceTest {

    private final LearningContextService service = new LearningContextService();

    @Test
    void oversizedContextDropsOldestEpisodesFirst() {
        LearningContextVo context = context(5, 3);

        service.trim(context, 200);

        assertTrue(context.isTruncated(), "超预算必须置 truncated");
        assertTrue(context.getEpisodes().size() < 5, "Episode 应该被裁掉一部分");
        assertTrue(context.getTokenEstimate() <= 200, "裁剪后必须回到预算内: " + context.getTokenEstimate());
    }

    @Test
    void generousBudgetKeepsEverything() {
        LearningContextVo context = context(2, 1);

        service.trim(context, 5000);

        assertFalse(context.isTruncated());
        assertTrue(context.getEpisodes().size() == 2 && context.getPatterns().size() == 1);
    }

    @Test
    void tinyBudgetEventuallyDropsPatternsToo() {
        LearningContextVo context = context(3, 3);

        service.trim(context, 80);

        assertTrue(context.isTruncated());
        assertTrue(context.getTokenEstimate() <= 80, "预算很小时也要收敛: " + context.getTokenEstimate());
    }

    @Test
    void estimateGrowsWithContent() {
        LearningContextVo small = context(1, 0);
        LearningContextVo large = context(3, 2);

        assertTrue(service.estimate(large) > service.estimate(small));
    }

    private LearningContextVo context(int episodes, int patterns) {
        LearningContextVo context = new LearningContextVo();
        LearningContextVo.ProblemBrief problem = new LearningContextVo.ProblemBrief();
        problem.setId(1L);
        problem.setTitle("A+B");
        problem.setDifficulty("简单");
        problem.setTags(List.of("数组", "模拟"));
        context.setProblem(problem);

        List<LearningContextVo.EpisodeBrief> episodeList = new ArrayList<>();
        for (int i = 0; i < episodes; i++) {
            LearningContextVo.EpisodeBrief brief = new LearningContextVo.EpisodeBrief();
            brief.setStartedAt("2026-10-19 21:00");
            brief.setOutcome("WA");
            brief.setAttempts(3);
            brief.setDurationMin(20);
            brief.setSummary("20 分钟内提交 3 次（通过 0 次），运行 5 次；首错为超出时间限制；查看题解 1 次。");
            episodeList.add(brief);
        }
        context.setEpisodes(episodeList);

        List<LearningContextVo.PatternBrief> patternList = new ArrayList<>();
        for (int i = 0; i < patterns; i++) {
            LearningContextVo.PatternBrief brief = new LearningContextVo.PatternBrief();
            brief.setType("ERROR_FOCUS");
            brief.setConfidence(0.8);
            brief.setDescription("最近失败中 超出时间限制 占 78%（7/9），是当前主要卡点");
            patternList.add(brief);
        }
        context.setPatterns(patternList);
        return context;
    }
}
