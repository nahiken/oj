package com.oj.judge.memory.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.vo.LearningContextVo;
import com.oj.judge.search.query.SearchHitVo;
import com.oj.judge.search.query.SearchQueryReq;
import com.oj.judge.search.query.SearchQueryResp;
import com.oj.judge.search.query.SearchQueryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 辅导用的「相关题」（spec D6，票 06）。
 *
 * <p>检索是**旁路**：ES 或 embedding 边车不可用时返回空列表，绝不把异常抛给
 * Context 组装，否则一次检索抖动就会让整个辅导流 503。
 *
 * <p>旁路还要有**自己的预算**：ES 的 socket 超时是 5s、边车冷启动可能更久，而
 * ai 等 Context 只有 3s。所以检索在独立线程里跑，超过
 * {@value #BUDGET_MS}ms 就放弃本次注入——宁可少一段相关题，也不能让辅导超时。
 *
 * <p>固定 {@value #LIMIT} 条，且排除当前题目——复习时再看到同一道题没有意义。
 * 抽够再截断，是为了排除当前题后仍然尽量凑满，而不是「命中 3 条、去掉本题只剩 2 条」。
 */
@Service
@Slf4j
public class RelatedProblemService {

    static final int LIMIT = 3;
    /** 多取一些，给「排除本题 + 去重」留余量。 */
    private static final int CANDIDATES = LIMIT + 7;
    private static final int MAX_QUERY_CHARS = 500;
    /** Hard cap for the whole search; ai gives Context 3s in total. */
    static final long BUDGET_MS = 1500;

    @Resource
    private SearchQueryService searchQueryService;

    /** Cached pool with daemon threads: a hung search must not queue the next one. */
    private final ExecutorService searchPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "related-problem-search");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdown() {
        searchPool.shutdownNow();
    }

    public List<LearningContextVo.ProblemBrief> find(Long problemId, LearningContextVo.ProblemBrief problem) {
        if (problem == null) {
            return new ArrayList<>();
        }
        String query = queryOf(problem);
        if (query.isBlank()) {
            return new ArrayList<>();
        }
        Future<List<LearningContextVo.ProblemBrief>> pending = searchPool.submit(() -> {
            SearchQueryReq request = new SearchQueryReq();
            request.setQuery(query);
            request.setMode("hybrid");
            request.setTopK(CANDIDATES);
            SearchQueryResp response = searchQueryService.query(request);
            return pick(problemId, response.getHits());
        });
        try {
            return pending.get(BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            log.info("evt=ai.related.timeout problemId={} budgetMs={}", problemId, BUDGET_MS);
            return new ArrayList<>();
        } catch (Exception e) {
            // One line, no stack trace: a down sidecar is an expected state, not a bug.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.info("evt=ai.related.degraded problemId={} reason={}", problemId, cause.toString());
            return new ArrayList<>();
        }
    }

    /** 标题 + 标签：题面正文不进查询，既省一次分词也避免把答案线索带进检索。 */
    static String queryOf(LearningContextVo.ProblemBrief problem) {
        StringBuilder sb = new StringBuilder();
        if (problem.getTitle() != null) {
            sb.append(problem.getTitle().trim());
        }
        for (String tag : problem.getTags() == null ? List.<String>of() : problem.getTags()) {
            if (tag != null && !tag.isBlank()) {
                sb.append(' ').append(tag.trim());
            }
        }
        String query = sb.toString().trim();
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }

    /**
     * package-private 让单测直接验证「排除本题 + 去重 + 固定条数」，不用起 ES。
     */
    List<LearningContextVo.ProblemBrief> pick(Long problemId, List<SearchHitVo> hits) {
        List<LearningContextVo.ProblemBrief> picked = new ArrayList<>();
        if (hits == null) {
            return picked;
        }
        Set<Long> seen = new HashSet<>();
        for (SearchHitVo hit : hits) {
            if (picked.size() >= LIMIT) {
                break;
            }
            Long id = hit.getProblemId();
            if (id == null || id.equals(problemId) || !seen.add(id)) {
                continue;
            }
            LearningContextVo.ProblemBrief brief = new LearningContextVo.ProblemBrief();
            brief.setId(id);
            brief.setTitle(hit.getTitle());
            brief.setDifficulty(hit.getDifficulty());
            brief.setTags(hit.getTags() == null ? new ArrayList<>() : hit.getTags());
            picked.add(brief);
        }
        return picked;
    }
}
