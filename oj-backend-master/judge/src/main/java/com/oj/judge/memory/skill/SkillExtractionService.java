package com.oj.judge.memory.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.memory.ai.AiClient;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.support.ProblemTags;
import com.oj.judge.model.entity.Problem;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 候选 Skill 抽取（契约 0006 §2，spec D10）。
 *
 * <p>分工是刻意的：judge 选候选、做规则校验、落库；Python 只做「文本进、文本出」。
 * 规则校验不调用任何模型，所以「LLM 乱写也进不了 DRAFT」是可复现的。
 */
@Service
public class SkillExtractionService {

    private static final int MAX_CANDIDATES = 20;
    private static final int MIN_STEPS = 3;
    private static final int MAX_CONTENT = 500;
    private static final int NGRAM = 8;

    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private SkillService skillService;
    @Resource
    private ProblemMapper problemMapper;

    @Resource
    private AiClient aiClient;

    public Map<String, Object> extract(Long uid) {
        List<Long> created = new ArrayList<>();
        Map<String, Integer> rejected = new LinkedHashMap<>();
        rejected.put("too_short", 0);
        rejected.put("too_long", 0);
        rejected.put("no_tags", 0);
        rejected.put("answer_leak", 0);
        rejected.put("ai_unavailable", 0);

        for (LearningEpisodeDO episode : episodeMapper.selectExtractionCandidates(MAX_CANDIDATES)) {
            if (skillMapper.countBySourceEpisode(episode.getId()) > 0) {
                continue;
            }
            Problem problem = problemMapper.selectById(episode.getProblemId());
            List<String> tags = problem == null ? Collections.emptyList() : ProblemTags.parse(problem.getTags());

            Candidate candidate;
            try {
                candidate = requestCandidate(problem, episode, tags);
            } catch (Exception e) {
                rejected.merge("ai_unavailable", 1, Integer::sum);
                continue;
            }

            String content = candidate.content == null ? "" : candidate.content.strip();
            if (candidate.steps.size() < MIN_STEPS) {
                rejected.merge("too_short", 1, Integer::sum);
                continue;
            }
            if (content.length() > MAX_CONTENT) {
                rejected.merge("too_long", 1, Integer::sum);
                continue;
            }
            if (tags.isEmpty()) {
                rejected.merge("no_tags", 1, Integer::sum);
                continue;
            }
            if (problem != null && leaks(content, problem.getAnswer())) {
                rejected.merge("answer_leak", 1, Integer::sum);
                continue;
            }

            String name = (problem == null ? "skill" : shortTitle(problem.getTitle())) + " :: " + tags.get(0);
            Long id = skillService.createFromEpisode(uid, name, content, JSONUtil.toJsonStr(tags),
                    JSONUtil.toJsonStr(candidate.steps), episode.getId(), candidate.model, candidate.promptVersion);
            if (id != null) {
                created.add(id);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("rejected", rejected);
        return result;
    }

    private Candidate requestCandidate(Problem problem, LearningEpisodeDO episode, List<String> tags) {
        JSONObject payload = new JSONObject();
        payload.set("title", problem == null ? "" : problem.getTitle());
        payload.set("tags", new JSONArray(tags));
        payload.set("summary", episode.getSummary() == null ? "" : episode.getSummary());
        payload.set("attempts", episode.getAttemptCount() == null ? 0 : episode.getAttemptCount());

        JSONObject json = aiClient.post("/internal/extract-skill", payload);
        Candidate candidate = new Candidate();
        candidate.content = json.getStr("content");
        candidate.model = json.getStr("model");
        // 来源版本只认 Python 的回应：本地再存一份常量，改 prompt 时会静默写错来源（ADR 0005）。
        candidate.promptVersion = json.getStr("prompt_version");
        JSONArray steps = json.getJSONArray("steps");
        if (steps != null) {
            for (Object step : steps) {
                candidate.steps.add(String.valueOf(step));
            }
        }
        return candidate;
    }

    /** 答案 8-gram 指纹：命中即视为泄露，丢弃候选。 */
    static boolean leaks(String content, String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String compactAnswer = answer.replaceAll("\\s+", "");
        String compactContent = content.replaceAll("\\s+", "");
        if (compactAnswer.length() < NGRAM) {
            return false;
        }
        for (int i = 0; i + NGRAM <= compactAnswer.length(); i++) {
            if (compactContent.contains(compactAnswer.substring(i, i + NGRAM))) {
                return true;
            }
        }
        return false;
    }

    private static String shortTitle(String title) {
        if (title == null || title.isBlank()) {
            return "skill";
        }
        return title.length() <= 40 ? title : title.substring(0, 40);
    }

    private static final class Candidate {
        private String content;
        private String model;
        private String promptVersion;
        private final List<String> steps = new ArrayList<>();
    }
}
