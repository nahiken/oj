package com.oj.judge.memory.context;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEpisodeSummaryMapper;
import com.oj.judge.memory.mapper.LearningPatternMapper;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.memory.model.LearningEpisodeSummaryDO;
import com.oj.judge.memory.model.LearningPatternDO;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.summary.EpisodeSummaryService;
import com.oj.judge.memory.support.ProblemTags;
import com.oj.judge.memory.vo.LearningContextVo;
import com.oj.judge.model.entity.Problem;

/**
 * Context 组装（契约 0004 §6）。
 *
 * <p>预算裁剪顺序：先丢最旧的 Episode，再丢置信度最低的 Pattern；题目本身不裁。
 * 被裁过就置 `truncated=true`，调用方知道自己看到的不是全量。
 */
@Service
public class LearningContextService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_SKILLS = 3;

    @Resource
    private ProblemMapper problemMapper;
    @Resource
    private LearningEpisodeMapper episodeMapper;
    @Resource
    private LearningPatternMapper patternMapper;
    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private LearningEpisodeSummaryMapper summaryMapper;
    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private RelatedProblemService relatedProblemService;

    public LearningContextVo build(Long uid, Long problemId) {
        LearningContextVo context = new LearningContextVo();
        context.setProblem(loadProblem(problemId));
        context.setRelatedProblems(relatedProblemService.find(problemId, context.getProblem()));
        if (uid != null) {
            context.setEpisodes(loadEpisodes(uid, context));
            context.setPatterns(loadPatterns(uid));
            context.setSkills(loadSkills(uid, context.getProblem(), context.getPatterns()));
        }
        trim(context, Math.max(64, properties.getContextTokenBudget()));
        return context;
    }

    /**
     * ACTIVE Skill 的匹配与排序（契约 0006 §8）。
     *
     * <p>顺序：标签重合数 → 是否命中当前薄弱 Pattern → 历史成功率 → 更新时间。
     * 「命中薄弱 Pattern」的定义：Skill 的某个标签出现在用户 `WEAK_TAG` Pattern 的描述里，
     * 即这条经验正好对着最近的薄弱点。个人 Skill 与平台 Skill 同场竞争，但只有本人的
     * PERSONAL 会出现在 SQL 结果里。
     */
    private List<LearningContextVo.SkillBrief> loadSkills(Long uid, LearningContextVo.ProblemBrief problem,
            List<LearningContextVo.PatternBrief> patterns) {
        List<LearningSkillDO> usable = skillMapper.selectUsable(uid);
        if (usable.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> problemTags = problem == null || problem.getTags() == null
                ? Collections.emptySet()
                : new HashSet<>(problem.getTags());

        List<Scored> scored = new ArrayList<>();
        for (LearningSkillDO skill : usable) {
            Set<String> skillTags = new HashSet<>(ProblemTags.parse(skill.getTags()));
            long overlap = skillTags.stream().filter(problemTags::contains).count();
            boolean patternHit = hitsWeakPattern(skillTags, patterns);
            double rate = skill.getUsageCount() == null || skill.getUsageCount() == 0
                    ? 0.0
                    : (double) skill.getSuccessCount() / skill.getUsageCount();
            scored.add(new Scored(skill, overlap, patternHit, rate));
        }
        scored.sort(Comparator
                .comparingLong(Scored::overlap).reversed()
                .thenComparing(Scored::patternHit, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingDouble(Scored::rate).reversed())
                .thenComparing(s -> s.skill().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<LearningContextVo.SkillBrief> briefs = new ArrayList<>();
        for (Scored candidate : scored) {
            if (briefs.size() >= MAX_SKILLS) {
                break;
            }
            LearningSkillDO skill = candidate.skill();
            LearningContextVo.SkillBrief brief = new LearningContextVo.SkillBrief();
            brief.setId(skill.getId());
            brief.setName(skill.getName());
            brief.setContent(skill.getContent());
            brief.setTags(ProblemTags.parse(skill.getTags()));
            briefs.add(brief);
        }
        return briefs;
    }

    /** 这条经验是否正对着用户当前的薄弱标签（Pattern 是统计特征，不参与审核）。 */
    private static boolean hitsWeakPattern(Set<String> skillTags,
            List<LearningContextVo.PatternBrief> patterns) {
        if (skillTags.isEmpty() || patterns == null) {
            return false;
        }
        for (LearningContextVo.PatternBrief pattern : patterns) {
            if (!"WEAK_TAG".equals(pattern.getType()) || pattern.getDescription() == null) {
                continue;
            }
            for (String tag : skillTags) {
                if (!tag.isBlank() && pattern.getDescription().contains(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Scored(LearningSkillDO skill, long overlap, boolean patternHit, double rate) {
    }

    private LearningContextVo.ProblemBrief loadProblem(Long problemId) {
        if (problemId == null || problemId <= 0) {
            return null;
        }
        Problem problem = problemMapper.selectById(problemId);
        if (problem == null) {
            return null;
        }
        LearningContextVo.ProblemBrief brief = new LearningContextVo.ProblemBrief();
        brief.setId(problem.getId());
        brief.setTitle(problem.getTitle());
        brief.setDifficulty(problem.getDifficulty());
        brief.setTags(ProblemTags.parse(problem.getTags()));
        return brief;
    }

    private List<LearningContextVo.EpisodeBrief> loadEpisodes(Long uid, LearningContextVo context) {
        List<LearningContextVo.EpisodeBrief> briefs = new ArrayList<>();
        for (LearningEpisodeDO episode : episodeMapper.selectRecent(uid, properties.getContextEpisodes())) {
            LearningContextVo.EpisodeBrief brief = new LearningContextVo.EpisodeBrief();
            brief.setStartedAt(episode.getStartedAt() == null ? null
                    : TIME_FORMAT.format(episode.getStartedAt().toInstant()));
            brief.setOutcome(episode.getOutcome());
            long durationMs = episode.getEndedAt() == null || episode.getStartedAt() == null ? 0L
                    : episode.getEndedAt().getTime() - episode.getStartedAt().getTime();
            brief.setDurationMin(Math.max(1L, Math.round(durationMs / 60000.0)));
            brief.setAttempts(episode.getAttemptCount() == null ? 0 : episode.getAttemptCount());
            // ADR 0005: prefer the LLM summary, fall back to the canonical RULE one.
            LearningEpisodeSummaryDO llm = summaryMapper.selectLlm(episode.getId(), EpisodeSummaryService.PROMPT_VERSION);
            boolean usedLlm = llm != null && llm.getContent() != null && !llm.getContent().isBlank();
            if (usedLlm) {
                context.setSummarySource(LearningContextVo.SOURCE_LLM);
            }
            brief.setSummary(usedLlm ? llm.getContent() : episode.getSummary());
            briefs.add(brief);
        }
        return briefs;
    }

    private List<LearningContextVo.PatternBrief> loadPatterns(Long uid) {
        Date since = new Date(System.currentTimeMillis() - properties.getPatternWindowDays() * 86400000L);
        List<LearningContextVo.PatternBrief> briefs = new ArrayList<>();
        for (LearningPatternDO pattern : patternMapper.selectTop(uid, since,
                properties.getContextPatterns())) {
            LearningContextVo.PatternBrief brief = new LearningContextVo.PatternBrief();
            brief.setType(pattern.getPatternType());
            brief.setDescription(pattern.getDescription());
            brief.setConfidence(pattern.getConfidence() == null ? 0.0
                    : pattern.getConfidence().doubleValue());
            briefs.add(brief);
        }
        return briefs;
    }

    /**
     * 预算裁剪：题目块固定，Episode 从最旧开始丢，然后丢低置信度 Pattern。
     *
     * <p>package-private 是为了单测能直接验证裁剪顺序，不用起 Spring 容器。
     */
    void trim(LearningContextVo context, int budget) {
        boolean trimmed = false;
        while (estimate(context) > budget && !context.getEpisodes().isEmpty()) {
            context.getEpisodes().remove(context.getEpisodes().size() - 1);
            trimmed = true;
        }
        while (estimate(context) > budget && !context.getPatterns().isEmpty()) {
            context.getPatterns().remove(context.getPatterns().size() - 1);
            trimmed = true;
        }
        // 相关题是可选旁路：要么整段注入、要么整段不注入。留一半会让「固定条数」
        // 变成谎话，也让 searchUsed 的语义变模糊。
        if (estimate(context) > budget && !context.getRelatedProblems().isEmpty()) {
            context.getRelatedProblems().clear();
            // Dropping an injected section is still a cut: reporting truncated=false
            // would tell the caller it saw the whole Context.
            trimmed = true;
        }
        context.setTruncated(trimmed);
        context.setTokenEstimate(estimate(context));
    }

    /**
     * 启发式估算：CJK 按 1.6 字符/token，其余按 4 字符/token。
     *
     * <p>不是分词器实测值——只用于预算裁剪与「有没有超预算」的判断，不写进效果指标。
     */
    public int estimate(LearningContextVo context) {
        int tokens = 40;
        if (context.getProblem() != null) {
            tokens += estimateText(context.getProblem().getTitle());
            tokens += estimateText(context.getProblem().getDifficulty());
            if (context.getProblem().getTags() != null) {
                for (String tag : context.getProblem().getTags()) {
                    tokens += estimateText(tag) + 2;
                }
            }
        }
        for (LearningContextVo.EpisodeBrief episode : context.getEpisodes()) {
            tokens += 12 + estimateText(episode.getSummary());
        }
        for (LearningContextVo.PatternBrief pattern : context.getPatterns()) {
            tokens += 8 + estimateText(pattern.getDescription());
        }
        for (LearningContextVo.ProblemBrief related : context.getRelatedProblems()) {
            // Same shape as token_budget.py's _related_cost: title plus tags.
            int relatedTokens = 12 + estimateText(related.getTitle());
            if (related.getTags() != null) {
                for (String tag : related.getTags()) {
                    relatedTokens += estimateText(tag) + 2;
                }
            }
            tokens += relatedTokens;
        }
        return tokens;
    }

    private int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk / 1.6 + other / 4.0);
    }
}
