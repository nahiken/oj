package com.oj.judge.memory.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.oj.judge.memory.model.LearningEpisodeDO;

/**
 * 摘要必须确定性：同字段两次调用逐字相同。否则证据无法断言，也无法复查。
 */
class EpisodeSummarizerTest {

    private final EpisodeSummarizer summarizer = new EpisodeSummarizer();

    @Test
    void acceptedSessionSummarisesAttemptsAndSolutionViews() {
        LearningEpisodeDO episode = episode("AC", 4, 6, 1, 0, 0);
        assertEquals("1 分钟内提交 4 次（通过 1 次），运行 6 次；首错为超出时间限制；查看题解 1 次。",
                summarizer.summarize(episode, "超出时间限制"));
    }

    @Test
    void exploreSessionWithoutSubmitSaysBrowsed() {
        LearningEpisodeDO episode = episode("EXPLORE", 0, 0, 0, 0, 0);
        assertEquals("1 分钟内浏览题目，未运行。", summarizer.summarize(episode, null));
    }

    @Test
    void runOnlySessionSaysNoSubmit() {
        LearningEpisodeDO episode = episode("EXPLORE", 0, 3, 0, 2, 120000L);
        assertEquals("1 分钟内运行 3 次，未提交；复制代码 2 次；页面停留 2 分钟。",
                summarizer.summarize(episode, null));
    }

    @Test
    void durationRoundsToMinutes() {
        LearningEpisodeDO episode = episode("WA", 1, 0, 0, 0, 0);
        episode.setEndedAt(new Date(episode.getStartedAt().getTime() + 32 * 60 * 1000L));
        assertTrue(summarizer.summarize(episode, "解答错误").startsWith("32 分钟内"));
    }

    @Test
    void twoAcEventsSummariseAsTwo() {
        LearningEpisodeDO episode = episode("AC", 3, 0, 0, 0, 0);
        episode.setAcCount(2);
        assertEquals("1 分钟内提交 3 次（通过 2 次），运行 0 次。", summarizer.summarize(episode, null));
    }

    @Test
    void sameInputProducesIdenticalText() {
        LearningEpisodeDO episode = episode("AC", 2, 1, 0, 0, 0);
        assertEquals(summarizer.summarize(episode, "解答错误"),
                summarizer.summarize(episode, "解答错误"));
    }

    private LearningEpisodeDO episode(String outcome, int attempts, int runs, int solutionViews,
            int copies, long dwellMs) {
        LearningEpisodeDO episode = new LearningEpisodeDO();
        episode.setOutcome(outcome);
        // 通过次数来自 ac 事件数（不是 outcome 标志位）
        episode.setAcCount("AC".equals(outcome) ? 1 : 0);
        episode.setAttemptCount(attempts);
        episode.setRunCount(runs);
        episode.setSolutionViews(solutionViews);
        episode.setCopyCount(copies);
        episode.setDwellMs(dwellMs);
        episode.setStartedAt(new Date(0L));
        episode.setEndedAt(new Date(0L));
        return episode;
    }
}
