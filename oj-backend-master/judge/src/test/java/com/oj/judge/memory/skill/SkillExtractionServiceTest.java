package com.oj.judge.memory.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.memory.ai.AiClient;
import com.oj.judge.memory.mapper.LearningEpisodeMapper;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningEpisodeDO;
import com.oj.judge.model.entity.Problem;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * 抽取拒绝矩阵（spec D8.3，票 04）。
 *
 * <p>每个用例只让**一个**桶命中，并断言候选没有落库——拒绝原因和「没写库」
 * 是同一件事的两面，只断言计数会放过「记了拒绝但仍然插了一行」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillExtractionServiceTest {

    private static final long UID = 7L;
    private static final long EPISODE_ID = 9001L;

    @Mock
    private LearningEpisodeMapper episodeMapper;
    @Mock
    private LearningSkillMapper skillMapper;
    @Mock
    private SkillService skillService;
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private AiClient aiClient;
    @InjectMocks
    private SkillExtractionService service;

    private Problem problem;

    @BeforeEach
    void setUp() {
        problem = new Problem();
        problem.setId(1L);
        problem.setTitle("两数之和");
        problem.setTags("[\"数组\",\"哈希\"]");
        problem.setAnswer("public int[] twoSum(int[] nums, int target) { return null; }");

        LearningEpisodeDO episode = new LearningEpisodeDO();
        episode.setId(EPISODE_ID);
        episode.setProblemId(1L);
        episode.setSummary("solved it");
        episode.setAttemptCount(2);
        when(episodeMapper.selectExtractionCandidates(anyInt())).thenReturn(List.of(episode));
        when(problemMapper.selectById(1L)).thenReturn(problem);
        when(skillMapper.countBySourceEpisode(EPISODE_ID)).thenReturn(0);
        when(skillService.createFromEpisode(any(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString())).thenReturn(5L);
    }

    @Test
    void tooShortWhenFewerThanThreeSteps() {
        stubCandidate("draw the table", steps("only one", "only two"));

        Map<String, Integer> rejected = rejected(service.extract(UID));

        assertEquals(1, rejected.get("too_short"));
        assertOnlyBucket(rejected, "too_short");
        verifyNoInteractions(skillService);
    }

    @Test
    void tooLongWhenContentExceedsLimit() {
        stubCandidate("a".repeat(501), steps("one", "two", "three"));

        Map<String, Integer> rejected = rejected(service.extract(UID));

        assertEquals(1, rejected.get("too_long"));
        assertOnlyBucket(rejected, "too_long");
        verifyNoInteractions(skillService);
    }

    @Test
    void noTagsWhenProblemHasNoUsableTags() {
        problem.setTags("");
        stubCandidate("draw the state table", steps("one", "two", "three"));

        Map<String, Integer> rejected = rejected(service.extract(UID));

        assertEquals(1, rejected.get("no_tags"));
        assertOnlyBucket(rejected, "no_tags");
        verifyNoInteractions(skillService);
    }

    @Test
    void answerLeakWhenContentRepeatsAnEightGram() {
        // The answer's opening 8 non-space characters, transplanted into the candidate.
        stubCandidate("try: publicint[]twoSum(int[] nums", steps("one", "two", "three"));

        Map<String, Integer> rejected = rejected(service.extract(UID));

        assertEquals(1, rejected.get("answer_leak"));
        assertOnlyBucket(rejected, "answer_leak");
        verifyNoInteractions(skillService);
    }

    @Test
    void aiUnavailableIsNotAContentRejection() {
        when(aiClient.post(anyString(), any())).thenThrow(new IllegalStateException("connection refused"));

        Map<String, Integer> rejected = rejected(service.extract(UID));

        assertEquals(1, rejected.get("ai_unavailable"));
        // It must not be misfiled as "the candidate was bad": the model never answered.
        assertOnlyBucket(rejected, "ai_unavailable");
        verifyNoInteractions(skillService);
    }

    @Test
    void acceptedCandidateIsPersistedAndCountedAsCreated() {
        stubCandidate("draw the state table, fill it, read the last cell",
                steps("draw the table", "fill it row by row", "read the answer"));

        Map<String, Object> result = service.extract(UID);

        assertNotNull(result.get("created"));
        assertEquals(List.of(5L), result.get("created"));
        assertOnlyBucket(rejected(result), null);
        verify(skillService).createFromEpisode(any(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString());
    }

    private void stubCandidate(String content, JSONArray steps) {
        JSONObject json = new JSONObject();
        json.set("content", content);
        json.set("steps", steps);
        json.set("model", "test-model");
        json.set("prompt_version", "extract-v1");
        when(aiClient.post(anyString(), any())).thenReturn(json);
    }

    private static JSONArray steps(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> rejected(Map<String, Object> result) {
        return (Map<String, Integer>) result.get("rejected");
    }

    /** All five buckets are always reported, so a missing key is itself a defect. */
    private static void assertOnlyBucket(Map<String, Integer> rejected, String hit) {
        assertEquals(5, rejected.size(), "每个拒绝桶都要出现: " + rejected);
        for (Map.Entry<String, Integer> entry : rejected.entrySet()) {
            if (entry.getKey().equals(hit)) {
                continue;
            }
            assertEquals(0, entry.getValue(), "只有 " + hit + " 应该命中: " + rejected);
        }
        if (hit != null) {
            assertTrue(rejected.get(hit) >= 1, hit + " 必须命中: " + rejected);
        }
    }
}
