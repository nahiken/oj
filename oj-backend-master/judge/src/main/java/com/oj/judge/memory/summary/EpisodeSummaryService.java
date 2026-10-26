package com.oj.judge.memory.summary;

import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.ai.AiClient;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEpisodeSummaryMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningEpisodeSummaryDO;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.json.JSONObject;

/**
 * LLM 摘要（ADR 0005，spec D12）。
 *
 * <p>规则摘要保持 canonical：这里只往独立表里追加 `source='LLM'` 的行，
 * 并用 `(episode_id, source, prompt_version)` 做幂等；换 prompt_version 就是新行，不覆盖。
 */
@Service
public class EpisodeSummaryService {

    public static final String PROMPT_VERSION = "summary-v1";
    private static final int MAX_PER_RUN = 20;

    @Resource
    private LearningEpisodeSummaryMapper summaryMapper;
    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private Snowflake snowflake;

    @Resource
    private AiClient aiClient;

    public int summarizeMissing() {
        return summarize(null);
    }

    /**
     * @param onlyEpisode when set, summarize just this Episode. Used by the
     *                    benchmark so a test run cannot backfill the whole table
     *                    with Fake summaries.
     */
    public int summarize(Long onlyEpisode) {
        int created = 0;
        List<Long> targets = onlyEpisode == null
                ? summaryMapper.selectMissingLlm(PROMPT_VERSION, MAX_PER_RUN)
                : (summaryMapper.selectLlm(onlyEpisode, PROMPT_VERSION) == null
                        ? List.of(onlyEpisode)
                        : List.of());
        for (Long episodeId : targets) {
            LearningEpisodeDO episode = episodeMapper.selectById(episodeId);
            if (episode == null) {
                continue;
            }
            JSONObject payload = new JSONObject();
            payload.set("problemId", episode.getProblemId());
            payload.set("outcome", episode.getOutcome());
            payload.set("attempts", episode.getAttemptCount());
            payload.set("summary", episode.getSummary() == null ? "" : episode.getSummary());

            JSONObject response;
            try {
                response = aiClient.post("/internal/summarize", payload);
            } catch (Exception e) {
                continue;
            }
            String content = response.getStr("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            LearningEpisodeSummaryDO row = new LearningEpisodeSummaryDO();
            row.setId(snowflake.nextId());
            row.setEpisodeId(episodeId);
            row.setSource(LearningEpisodeSummaryDO.SOURCE_LLM);
            row.setModel(response.getStr("model"));
            row.setPromptVersion(response.getStr("prompt_version", PROMPT_VERSION));
            row.setContent(content);
            row.setTokenEstimate(Math.max(1, content.length() / 2));
            row.setCreatedAt(new Date());
            created += summaryMapper.insertIgnore(row);
        }
        return created;
    }

    public long countLlm() {
        return summaryMapper.countLlm(PROMPT_VERSION);
    }

}
