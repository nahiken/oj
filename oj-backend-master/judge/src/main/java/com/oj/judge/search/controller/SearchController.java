package com.oj.judge.search.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oj.common.auth.annotation.RoleCheck;
import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.auth.constant.UserConstant;
import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.dto.R;
import com.oj.judge.memory.skill.search.SkillReindexService;
import com.oj.judge.memory.skill.search.SkillSearchResp;
import com.oj.judge.memory.skill.search.SkillSearchService;
import com.oj.judge.search.metrics.SearchMetrics;
import com.oj.judge.search.query.SearchQueryReq;
import com.oj.judge.search.query.SearchQueryResp;
import com.oj.judge.search.query.SearchQueryService;
import com.oj.judge.search.reindex.ReindexService;

/**
 * 检索接口（契约 0005 §4.1）。网关路径：`/api/judge/search/**`，不需要改网关。
 *
 * <p>错误码不走 HTTP 200 + body code 的房屋惯例，见 {@code SearchExceptionHandler}：
 * 503/501/400 是给上游机器看的契约。
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchQueryService queryService;
    private final ReindexService reindexService;
    private final SkillSearchService skillSearchService;
    private final SkillReindexService skillReindexService;
    private final SearchMetrics metrics;

    public SearchController(SearchQueryService queryService, ReindexService reindexService,
            SkillSearchService skillSearchService, SkillReindexService skillReindexService, SearchMetrics metrics) {
        this.queryService = queryService;
        this.reindexService = reindexService;
        this.skillSearchService = skillSearchService;
        this.skillReindexService = skillReindexService;
        this.metrics = metrics;
    }

    @PostMapping("/query")
    @TokenCheck
    public R<SearchQueryResp> query(@RequestBody(required = false) SearchQueryReq request) {
        return R.ok(queryService.query(request));
    }

    /** 全量重建：评估脚本用它把索引重置到已知状态（契约 0005 §3.1）。 */
    @PostMapping("/admin/reindex")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<ReindexService.ReindexResult> reindex() {
        return R.ok(reindexService.reindex());
    }

    @GetMapping("/metrics")
    @TokenCheck
    public R<Map<String, Object>> metrics() {
        return R.ok(metrics.snapshot());
    }

    /**
     * Skill 检索（spec D20）。网关路径 `/api/judge/search/skill`。
     *
     * <p>可见性由查询条件决定：平台 Skill + 自己的个人 Skill；别人的个人 Skill
     * 在 ES 里就被过滤掉，不会出现在返回列表里。
     */
    @GetMapping("/skill")
    @TokenCheck
    public R<SkillSearchResp> searchSkill(@RequestParam("q") String q,
            @RequestParam(value = "topK", required = false) Integer topK) {
        return R.ok(skillSearchService.search(TokenUtils.getCurrentUid(), q, topK));
    }

    /** Skill 索引全量重建（spec D20 的兜底通路）。 */
    @PostMapping("/skill/admin/reindex")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<SkillReindexService.ReindexResult> reindexSkills() {
        return R.ok(skillReindexService.reindex());
    }
}
