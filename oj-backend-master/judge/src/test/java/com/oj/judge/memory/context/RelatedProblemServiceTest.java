package com.oj.judge.memory.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oj.judge.memory.vo.LearningContextVo;
import com.oj.judge.search.SearchErrorCode;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.query.SearchHitVo;
import com.oj.judge.search.query.SearchQueryResp;
import com.oj.judge.search.query.SearchQueryService;

/**
 * `[相关题]` 的选择规则与降级（spec D6，票 06）。
 *
 * <p>降级是这张票的一半：检索挂掉必须返回空列表而不是抛异常，否则辅导流会被
 * 一个旁路功能拖成 503。
 */
@ExtendWith(MockitoExtension.class)
class RelatedProblemServiceTest {

    @Mock
    private SearchQueryService searchQueryService;
    @InjectMocks
    private RelatedProblemService service;

    @Test
    void excludesCurrentProblemDedupesAndCapsAtThree() {
        List<SearchHitVo> hits = List.of(
                hit(1L, "itself"),
                hit(2L, "first"),
                hit(2L, "duplicate"),
                hit(3L, "second"),
                hit(4L, "third"),
                hit(5L, "fourth"));

        List<LearningContextVo.ProblemBrief> picked = service.pick(1L, hits);

        assertEquals(List.of(2L, 3L, 4L), ids(picked));
        assertEquals("first", picked.get(0).getTitle());
    }

    @Test
    void returnsEmptyWhenSearchIsDown() {
        when(searchQueryService.query(any()))
                .thenThrow(new SearchException(SearchErrorCode.SEARCH_DISABLED, "search is off"));

        List<LearningContextVo.ProblemBrief> picked = service.find(1L, problem());

        assertTrue(picked.isEmpty());
    }

    @Test
    void returnsEmptyWhenEmbeddingSidecarIsDown() {
        // hybrid needs both paths; the sidecar failure surfaces as a SearchException.
        when(searchQueryService.query(any()))
                .thenThrow(new SearchException(SearchErrorCode.EMBEDDING_UNAVAILABLE, "sidecar refused"));

        assertTrue(service.find(1L, problem()).isEmpty());
    }

    @Test
    void hangingSearchIsDroppedWithinItsOwnBudget() {
        when(searchQueryService.query(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return new SearchQueryResp();
        });

        long started = System.currentTimeMillis();
        List<LearningContextVo.ProblemBrief> picked = service.find(1L, problem());
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(picked.isEmpty());
        assertTrue(elapsed >= RelatedProblemService.BUDGET_MS - 100, "gave up too early: " + elapsed);
        assertTrue(elapsed < 4_000, "the bypass held the tutor for " + elapsed + "ms");
    }

    @Test
    void returnsEmptyWithoutAProblem() {
        assertTrue(service.find(1L, null).isEmpty());
    }

    @Test
    void queryIsTitlePlusTags() {
        assertEquals("两数之和 数组 哈希", RelatedProblemService.queryOf(problem()));
    }

    private static LearningContextVo.ProblemBrief problem() {
        LearningContextVo.ProblemBrief problem = new LearningContextVo.ProblemBrief();
        problem.setId(1L);
        problem.setTitle("两数之和");
        problem.setTags(List.of("数组", "哈希"));
        return problem;
    }

    private static SearchHitVo hit(Long id, String title) {
        SearchHitVo vo = new SearchHitVo();
        vo.setProblemId(id);
        vo.setTitle(title);
        vo.setDifficulty("简单");
        vo.setTags(new ArrayList<>(List.of("数组")));
        return vo;
    }

    private static List<Long> ids(List<LearningContextVo.ProblemBrief> briefs) {
        List<Long> ids = new ArrayList<>();
        for (LearningContextVo.ProblemBrief brief : briefs) {
            ids.add(brief.getId());
        }
        return ids;
    }
}
