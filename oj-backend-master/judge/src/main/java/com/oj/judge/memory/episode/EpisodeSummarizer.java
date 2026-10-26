package com.oj.judge.memory.episode;

import org.springframework.stereotype.Component;

import com.oj.judge.memory.model.LearningEpisodeDO;

/**
 * 规则摘要（契约 0004 §4.3）。
 *
 * <p>确定性：同一 Episode 字段两次调用结果逐字相同（无随机、无当前时间）。
 * LLM 摘要留给 P6，届时写 `summary_source=LLM` 并提高 `summary_version`。
 */
@Component
public class EpisodeSummarizer {

    public static final String SOURCE_RULE = "RULE";
    public static final int VERSION = 1;

    /**
     * @param firstFailLabel 会话内首次失败的中文类型（如「超出时间限制」），无失败传 null
     */
    public String summarize(LearningEpisodeDO episode, String firstFailLabel) {
        long durationMs = Math.max(0L, episode.getEndedAt().getTime() - episode.getStartedAt().getTime());
        long minutes = Math.max(1L, Math.round(durationMs / 60000.0));

        StringBuilder sb = new StringBuilder();
        sb.append(minutes).append(" 分钟内");

        int attempts = value(episode.getAttemptCount());
        int runs = value(episode.getRunCount());
        // 通过次数用会话内 ac 事件数：一次会话里两次 AC 不能被写成「通过 1 次」（复审 P13b）
        int ac = value(episode.getAcCount());

        if (attempts > 0) {
            sb.append("提交 ").append(attempts).append(" 次（通过 ").append(ac).append(" 次），运行 ")
                    .append(runs).append(" 次");
        } else if (runs > 0) {
            sb.append("运行 ").append(runs).append(" 次，未提交");
        } else {
            sb.append("浏览题目，未运行");
        }

        if (firstFailLabel != null && !firstFailLabel.isBlank()) {
            sb.append("；首错为").append(firstFailLabel);
        }
        int solutionViews = value(episode.getSolutionViews());
        if (solutionViews > 0) {
            sb.append("；查看题解 ").append(solutionViews).append(" 次");
        }
        int copies = value(episode.getCopyCount());
        if (copies > 0) {
            sb.append("；复制代码 ").append(copies).append(" 次");
        }
        long dwellMs = episode.getDwellMs() == null ? 0L : episode.getDwellMs();
        if (dwellMs > 0) {
            sb.append("；页面停留 ").append(Math.max(1L, Math.round(dwellMs / 60000.0))).append(" 分钟");
        }
        sb.append("。");
        return sb.toString();
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }
}
