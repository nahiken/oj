package com.oj.judge.memory.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningEpisodeSummaryMapper;
import com.oj.judge.memory.mapper.LearningPatternMapper;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningPatternDO;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.vo.LearningContextVo;
import com.oj.judge.model.entity.Problem;

/**
 * Skill 排序矩阵（spec D8.3，票 04）：标签重合 → 薄弱 Pattern → 成功率。
 *
 * <p>每档单独构造，保证「排序靠后的一档」不会碰巧把前面的用例也解释通。
 * 断言只看 {@code build()} 返回的公开结果，不碰内部比较器。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningContextServiceSkillRankTest {

    private static final long UID = 7L;
    private static final long PROBLEM_ID = 1L;

    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private LearningEpisodeMapper episodeMapper;
    @Mock
    private LearningPatternMapper patternMapper;
    @Mock
    private LearningSkillMapper skillMapper;
    @Mock
    private LearningEpisodeSummaryMapper summaryMapper;
    @Mock
    private LearningMemoryProperties properties;
    @Mock
    private RelatedProblemService relatedProblemService;
    @InjectMocks
    private LearningContextService service;

    private Problem problem;

    @BeforeEach
    void setUp() {
        problem = new Problem();
        problem.setId(PROBLEM_ID);
        problem.setTitle("两数之和");
        problem.setDifficulty("简单");
        problem.setTags("[\"数组\",\"哈希\"]");

        when(problemMapper.selectById(PROBLEM_ID)).thenReturn(problem);
        when(episodeMapper.selectRecent(anyLong(), anyInt())).thenReturn(new ArrayList<>());
        when(patternMapper.selectTop(anyLong(), any(Date.class), anyInt())).thenReturn(new ArrayList<>());
        when(properties.getContextTokenBudget()).thenReturn(5000);
        lenient().when(properties.getContextEpisodes()).thenReturn(3);
        lenient().when(properties.getContextPatterns()).thenReturn(3);
        lenient().when(relatedProblemService.find(anyLong(), any())).thenReturn(new ArrayList<>());
    }

    @Test
    void tagOverlapOutranksSuccessRate() {
        // Same zero pattern hits, so only overlap and rate can decide.
        stubSkills(skill("tag-hit", "[\"数组\"]", 10, 1), skill("veteran", "[\"图\"]", 100, 100));

        assertEquals(List.of("tag-hit", "veteran"), rankedNames());
    }

    @Test
    void weakPatternHitOutranksSuccessRateWhenNoTagOverlap() {
        // Neither skill shares a tag with the problem, so overlap ties at 0.
        problem.setTags("[\"字符串\"]");
        stubPatterns(pattern("WEAK_TAG", "最近失败中 哈希 占 80%（8/10）"));
        stubSkills(skill("veteran", "[\"图\"]", 10, 10), skill("weak-match", "[\"哈希\"]", 10, 1));

        assertEquals(List.of("weak-match", "veteran"), rankedNames());
    }

    @Test
    void successRateBreaksTheTieWhenNeitherApplies() {
        problem.setTags("[\"字符串\"]");
        stubSkills(skill("often-works", "[\"图\"]", 10, 8), skill("rarely-works", "[\"树\"]", 10, 2));

        assertEquals(List.of("often-works", "rarely-works"), rankedNames());
    }

    private List<String> rankedNames() {
        when(properties.getContextTokenBudget()).thenReturn(5000);
        LearningContextVo context = service.build(UID, PROBLEM_ID);
        List<String> names = new ArrayList<>();
        for (LearningContextVo.SkillBrief brief : context.getSkills()) {
            names.add(brief.getName());
        }
        return names;
    }

    private void stubSkills(LearningSkillDO... skills) {
        List<LearningSkillDO> rows = List.of(skills);
        when(skillMapper.selectUsable(UID)).thenReturn(rows);
    }

    private void stubPatterns(LearningPatternDO... patterns) {
        when(patternMapper.selectTop(anyLong(), any(Date.class), anyInt())).thenReturn(List.of(patterns));
    }

    private static LearningSkillDO skill(String name, String tagsJson, int usage, int success) {
        LearningSkillDO row = new LearningSkillDO();
        row.setId((long) name.hashCode());
        row.setName(name);
        row.setTags(tagsJson);
        row.setContent("1. a 2. b 3. c");
        row.setStatus(LearningSkillDO.STATUS_ACTIVE);
        row.setScope(LearningSkillDO.SCOPE_PERSONAL);
        row.setOwnerUid(UID);
        row.setUsageCount(usage);
        row.setSuccessCount(success);
        row.setUpdatedAt(new Date(1_700_000_000_000L));
        return row;
    }

    private static LearningPatternDO pattern(String type, String description) {
        LearningPatternDO row = new LearningPatternDO();
        row.setPatternType(type);
        row.setDescription(description);
        row.setConfidence(new BigDecimal("0.9"));
        return row;
    }
}
