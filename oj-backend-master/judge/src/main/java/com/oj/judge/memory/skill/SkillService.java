package com.oj.judge.memory.skill;

import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillAuditDO;
import com.oj.judge.memory.model.LearningSkillDO;

import cn.hutool.core.lang.Snowflake;

/**
 * Skill 生命周期（契约 0006 §8，spec D9）。
 *
 * <p>状态机用**显式转移表**，不是散落的 if：新增一条转移必须同时改这里和单测，
 * 否则非法转移会被静默接受。
 *
 * <p>谁能做什么也在这里：只有本人能提交自己的 PERSONAL Skill，只有管理员能
 * 批准/打回/下架——自审自批一律拒绝。
 */
@Service
public class SkillService {

    /** DRAFT -> REVIEW（本人提交） */
    public static final String SUBMIT = "DRAFT->REVIEW";
    /** REVIEW -> ACTIVE（管理员批准） */
    public static final String APPROVE = "REVIEW->ACTIVE";
    /** REVIEW -> DRAFT（管理员打回，必须给理由） */
    public static final String REJECT = "REVIEW->DRAFT";
    /** ACTIVE -> DEPRECATED（管理员下架） */
    public static final String DEPRECATE = "ACTIVE->DEPRECATED";

    private static final Set<String> ALLOWED = Set.of(SUBMIT, APPROVE, REJECT, DEPRECATE);

    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private Snowflake snowflake;
    /** 索引双写（spec D20）：只在事务提交后写，失败不回滚状态机。 */
    @Resource
    private SkillIndexWriter skillIndexWriter;

    public Long create(Long uid, String name, String scope, String content, String tags, String stepsJson) {
        ThrowUtils.throwIf(name == null || name.isBlank(), HttpCodeEnum.BAD_REQUEST, "name 不能为空");
        ThrowUtils.throwIf(content == null || content.isBlank(), HttpCodeEnum.BAD_REQUEST, "content 不能为空");
        ThrowUtils.throwIf(content.length() > 500, HttpCodeEnum.BAD_REQUEST, "content 不能超过 500 字");
        boolean platform = LearningSkillDO.SCOPE_PLATFORM.equals(scope);
        ThrowUtils.throwIf(!platform && !LearningSkillDO.SCOPE_PERSONAL.equals(scope),
                HttpCodeEnum.BAD_REQUEST, "scope 只能是 PERSONAL 或 PLATFORM");

        Date now = new Date();
        LearningSkillDO skill = new LearningSkillDO();
        skill.setId(snowflake.nextId());
        skill.setName(name);
        skill.setScope(platform ? LearningSkillDO.SCOPE_PLATFORM : LearningSkillDO.SCOPE_PERSONAL);
        skill.setOwnerUid(platform ? null : uid);
        skill.setStatus(LearningSkillDO.STATUS_DRAFT);
        skill.setContent(content);
        skill.setStepsJson(stepsJson);
        skill.setTags(tags);
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        skillMapper.insert(skill);
        audit(skill.getId(), null, LearningSkillDO.STATUS_DRAFT, uid, null, now);
        return skill.getId();
    }

    /** 由抽取流程调用：同一 Episode 只产出一次候选。 */
    public Long createFromEpisode(Long uid, String name, String content, String tags, String stepsJson,
            Long episodeId, String model, String promptVersion) {
        if (episodeId != null && skillMapper.countBySourceEpisode(episodeId) > 0) {
            return null;
        }
        Long id = create(uid, name, LearningSkillDO.SCOPE_PERSONAL, content, tags, stepsJson);
        // source/model/prompt_version 只在抽取路径上写一次：直接写库，不先改一个会被丢弃的局部对象。
        skillMapper.updateProvenance(id, episodeId, model, promptVersion);
        return id;
    }

    public LearningSkillDO require(Long id) {
        LearningSkillDO skill = skillMapper.selectById(id);
        ThrowUtils.throwIf(skill == null, HttpCodeEnum.NOT_EXIST, "Skill 不存在");
        return skill;
    }

    public List<LearningSkillDO> list(String status, String scope) {
        return skillMapper.selectList(blankToNull(status), blankToNull(scope));
    }

    /**
     * Skill 真正被注入 prompt 时调用（不是被列表查询时）。
     *
     * <p>只接受「调用者可见且 ACTIVE」的 id：越权 id 静默忽略，避免把它变成探测接口。
     * 同时落一行 usage，AC 之后据此归因 success_count（10 分钟窗口）。
     */
    public int markUsed(Long uid, Long problemId, List<Long> ids) {
        int updated = 0;
        Date now = new Date();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            LearningSkillDO skill = skillMapper.selectById(id);
            if (skill == null || !isUsable(skill, uid)) {
                continue;
            }
            skillMapper.incrementUsage(id);
            if (problemId != null && problemId > 0) {
                // 没有题目就无法归因，但仍应记「用过」。
                skillMapper.insertUsage(snowflake.nextId(), uid, problemId, id, now);
            }
            updated++;
        }
        return updated;
    }

    @Transactional
    public LearningSkillDO submit(Long uid, Long id) {
        LearningSkillDO skill = require(id);
        ThrowUtils.throwIf(!isOwner(skill, uid), HttpCodeEnum.NO_AUTH, "只能提交自己的 Skill");
        return transit(skill, LearningSkillDO.STATUS_REVIEW, uid, null);
    }

    @Transactional
    public LearningSkillDO review(Long reviewerUid, Long id, boolean approve, String reason) {
        LearningSkillDO skill = require(id);
        // spec US14：任何人都不能自审自批（管理员也不行）。
        ThrowUtils.throwIf(skill.getOwnerUid() != null && skill.getOwnerUid().equals(reviewerUid),
                HttpCodeEnum.NO_AUTH, "不能审核自己的 Skill");
        if (approve) {
            LearningSkillDO approved = transit(skill, LearningSkillDO.STATUS_ACTIVE, reviewerUid, null);
            // 双写：批准即进索引（spec D20）。写在提交之后，索引失败不回滚状态，MySQL 才是真相。
            skillIndexWriter.indexAfterCommit(approved);
            return approved;
        }
        ThrowUtils.throwIf(reason == null || reason.isBlank(), HttpCodeEnum.BAD_REQUEST, "打回必须给理由");
        LearningSkillDO rejected = transit(skill, LearningSkillDO.STATUS_DRAFT, reviewerUid, reason);
        skillIndexWriter.deleteAfterCommit(rejected.getId());
        return rejected;
    }

    @Transactional
    public LearningSkillDO deprecate(Long reviewerUid, Long id) {
        LearningSkillDO skill = require(id);
        LearningSkillDO deprecated = transit(skill, LearningSkillDO.STATUS_DEPRECATED, reviewerUid, null);
        // 下架必须从索引消失，否则「已下架却仍被 agent 引用」是用户可见的错误。
        skillIndexWriter.deleteAfterCommit(deprecated.getId());
        return deprecated;
    }

    private LearningSkillDO transit(LearningSkillDO skill, String target, Long actorUid, String reason) {
        String from = skill.getStatus();
        String edge = from + "->" + target;
        if (!ALLOWED.contains(edge)) {
            throw new BusinessException(HttpCodeEnum.SKILL_STATE);
        }
        Date now = new Date();
        skillMapper.updateStatus(skill.getId(), target, reason, now);
        audit(skill.getId(), from, target, actorUid, reason, now);
        skill.setStatus(target);
        skill.setRejectReason(reason);
        skill.setUpdatedAt(now);
        return skill;
    }

    /** 只有本人的 PERSONAL Skill 归自己；平台 Skill 不归任何用户。 */
    private static boolean isOwner(LearningSkillDO skill, Long uid) {
        return !LearningSkillDO.SCOPE_PLATFORM.equals(skill.getScope())
                && uid != null && uid.equals(skill.getOwnerUid());
    }

    private static boolean isUsable(LearningSkillDO skill, Long uid) {
        if (!LearningSkillDO.STATUS_ACTIVE.equals(skill.getStatus())) {
            return false;
        }
        return LearningSkillDO.SCOPE_PLATFORM.equals(skill.getScope()) || isOwner(skill, uid);
    }

    private void audit(Long skillId, String from, String to, Long actorUid, String reason, Date at) {
        LearningSkillAuditDO row = new LearningSkillAuditDO();
        row.setId(snowflake.nextId());
        row.setSkillId(skillId);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setActorUid(actorUid);
        row.setReason(reason);
        row.setCreatedAt(at);
        skillMapper.insertAudit(row);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
