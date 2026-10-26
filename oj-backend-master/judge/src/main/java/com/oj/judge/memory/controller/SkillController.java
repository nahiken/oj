package com.oj.judge.memory.controller;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import com.oj.judge.memory.dto.SkillCreateReq;
import com.oj.judge.memory.dto.SkillReviewReq;
import com.oj.judge.memory.dto.SkillUsedReq;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.SkillExtractionService;
import com.oj.judge.memory.skill.SkillPromotionService;
import com.oj.judge.memory.skill.SkillService;

import cn.hutool.json.JSONUtil;

/**
 * Skill 生命周期接口（契约 0006 §2）。
 *
 * <p>网关路径 `/api/judge/learning/skill/**`。审核不建前端页面，走脚本。
 */
@RestController
@RequestMapping("/learning/skill")
public class SkillController {

    @Resource
    private SkillService skillService;
    @Resource
    private SkillExtractionService extractionService;
    @Resource
    private SkillPromotionService promotionService;

    @PostMapping
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<Long> create(@RequestBody SkillCreateReq req) {
        Long uid = TokenUtils.getCurrentUid();
        String tags = req.getTags() == null ? null : JSONUtil.toJsonStr(req.getTags());
        return R.ok(skillService.create(uid, req.getName(), req.getScope(), req.getContent(), tags, null));
    }

    @PostMapping("/extract")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<Map<String, Object>> extract() {
        return R.ok(extractionService.extract(TokenUtils.getCurrentUid()));
    }

    @PostMapping("/used")
    @TokenCheck
    public R<Integer> used(@RequestBody SkillUsedReq req) {
        // 越权 id 在 Service 里静默忽略：这里不区分「不可见」与「不存在」。
        return R.ok(skillService.markUsed(TokenUtils.getCurrentUid(), req.getProblemId(), req.getIds()));
    }

    @GetMapping("/list")
    @TokenCheck
    public R<List<LearningSkillDO>> list(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "scope", required = false) String scope) {
        return R.ok(skillService.list(status, scope));
    }

    @PostMapping("/{id}/submit")
    @TokenCheck
    public R<LearningSkillDO> submit(@PathVariable("id") Long id) {
        return R.ok(skillService.submit(TokenUtils.getCurrentUid(), id));
    }

    @PostMapping("/{id}/review")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<LearningSkillDO> review(@PathVariable("id") Long id, @RequestBody SkillReviewReq req) {
        return R.ok(skillService.review(TokenUtils.getCurrentUid(), id, req.isApprove(), req.getReason()));
    }

    @PostMapping("/{id}/deprecate")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<LearningSkillDO> deprecate(@PathVariable("id") Long id) {
        return R.ok(skillService.deprecate(TokenUtils.getCurrentUid(), id));
    }

    /**
     * 晋升一条个人 Skill 到公共池（spec D18/D21/D22/D23）。
     *
     * <p>幂等：同一条重复调用返回同一条 PLATFORM 行，不新建第二条。
     * 原作者被拒（即使他是管理员），门槛不满足被拒。
     */
    @PostMapping("/{id}/promote")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<LearningSkillDO> promote(@PathVariable("id") Long id) {
        return R.ok(promotionService.promote(TokenUtils.getCurrentUid(), id));
    }

    /** 管理员视角的候选清单：谁快够格、差在哪（含窗口证据）。 */
    @GetMapping("/promotion/candidates")
    @TokenCheck
    @RoleCheck(mustRole = UserConstant.ADMIN_ROLE)
    public R<List<SkillPromotionService.Candidate>> candidates(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(promotionService.candidates(limit == null ? 20 : limit));
    }
}
