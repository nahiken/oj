package com.oj.judge.memory.skill;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.search.SkillIndexer;

import cn.hutool.core.lang.Snowflake;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 晋升：个人 Skill 复用达标 → 公共池新建一条 PLATFORM 行（spec D18/D21/D23）。
 *
 * <p>四条规则写死在这里，因为它们都是「不能靠调用方自觉」的：
 * <ol>
 *   <li>门槛（≥3 个不同 uid、`usage_count ≥ 10`、窗口成功率 ≥ 0.6、30 天窗口）不达标就拒绝；</li>
 *   <li>重复晋升幂等——返回已经存在的那条，不新建、不报错；</li>
 *   <li>原作者不能批准自己的 Skill 上公共池（沿用「禁止自审自批」）；</li>
 *   <li>个人原行**保持不变**：晋升只是新增一行，作者仍能继续改自己的版本。</li>
 * </ol>
 *
 * <p>为什么不改个人行的 scope：改了就回滚不回去，而且作者会突然失去自己的 Skill。
 * 「新建 + origin 指回」让两条记录各自演化，代价是内容会分叉——这是 spec 明确接受的。
 */
@Slf4j
@Service
public class SkillPromotionService {

    /** 每日扫描的审批人：系统。audit.actor_uid 是 NOT NULL，用 0 表示「不是人批的」。 */
    public static final long SYSTEM_ACTOR = 0L;

    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private LearningMemoryProperties properties;
    @Resource
    private Snowflake snowflake;
    @Resource
    private SkillIndexer skillIndexer;
    /** 写事务边界：插入公共池行 + 审计行在一个事务里（独立 Bean 才会真的走代理）。 */
    @Resource
    private SkillPromotionWriter writer;

    /** 一个候选 + 它为什么够格（管理员要看的正是这组数）。 */
    @Data
    public static class Candidate {
        private LearningSkillDO skill;
        private int windowUsers;
        private int windowUsage;
        private int windowSuccess;
        private double windowSuccessRate;
        private boolean eligible;
        private String rejectReason;
    }

    @Data
    public static class ScanResult {
        private int candidates;
        private int promoted;
        private int skipped;
        private List<Long> promotedIds = new ArrayList<>();
    }

    /** 管理员视角：谁快够格了、差在哪。按 usage_count 降序。 */
    public List<Candidate> candidates(int limit) {
        List<Long> ids = skillMapper.selectPromotionCandidates(properties.getSkillPromotionMinUsage(), limit);
        List<Candidate> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            LearningSkillDO skill = skillMapper.selectById(id);
            if (skill != null) {
                out.add(evaluate(skill));
            }
        }
        return out;
    }

    /**
     * 晋升一条。`actorUid` 是审批人（手动时是管理员，每日扫描时是 {@link #SYSTEM_ACTOR}）。
     *
     * @return 公共池里的那一条（新创建的，或之前已经晋升过的同一条）
     */
    public LearningSkillDO promote(Long actorUid, Long skillId) {
        LearningSkillDO skill = skillMapper.selectById(skillId);
        ThrowUtils.throwIf(skill == null, HttpCodeEnum.NOT_EXIST, "Skill 不存在");

        ThrowUtils.throwIf(!LearningSkillDO.SCOPE_PERSONAL.equals(skill.getScope()),
                HttpCodeEnum.SKILL_STATE, "只有个人 Skill 可以晋升");
        ThrowUtils.throwIf(!LearningSkillDO.STATUS_ACTIVE.equals(skill.getStatus()),
                HttpCodeEnum.SKILL_STATE, "只有 ACTIVE 的个人 Skill 可以晋升");
        // 原作者（即使他是管理员）不能自己批自己：spec D22。
        // 这一条排在幂等之前：已经晋升过的 Skill，作者再调一次也必须是 403，
        // 否则「禁止自批」只在第一次成立，重复调用就变成了成功。
        ThrowUtils.throwIf(skill.getOwnerUid() != null && skill.getOwnerUid().equals(actorUid),
                HttpCodeEnum.NO_AUTH, "原作者不能批准自己的 Skill 上公共池");

        // 幂等：已经晋升过就直接返回，不产生第二条、不再记一次审计。
        LearningSkillDO existing = skillMapper.selectByOrigin(skillId, LearningSkillDO.SCOPE_PLATFORM);
        if (existing != null) {
            return existing;
        }

        Candidate candidate = evaluate(skill);
        ThrowUtils.throwIf(!candidate.isEligible(), HttpCodeEnum.SKILL_PROMOTION_GATE,
                candidate.getRejectReason());

        LearningSkillDO promoted = buildPromoted(skill);
        try {
            // 插入 + 审计在独立的 Bean 里同一个事务（`this.` 调用会绕过 @Transactional 代理）。
            writer.insertPromoted(promoted, new PromotionContext(actorUid, skillId));
        } catch (DuplicateKeyException e) {
            // 并发审批：另一个请求先插入了。唯一索引挡住了第二条，读回已有行。
            LearningSkillDO winner = skillMapper.selectByOrigin(skillId, LearningSkillDO.SCOPE_PLATFORM);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
        // 双写：晋升后 search_skill 立刻能搜到它（票 05 的验收项）。
        // 事务已提交，这里失败也只会留下「数据库有、索引没有」，可由全量重建修好。
        skillIndexer.index(promoted);
        log.info("evt=memory.skill.promoted origin={} new={} actor={} rate={}", skillId, promoted.getId(), actorUid,
                candidate.getWindowSuccessRate());
        return promoted;
    }

    /**
     * 每日扫描：按 `usage_count` 降序处理，单次最多 {@code skill-promotion-limit} 条。
     *
     * <p>一条失败不影响其余：门槛不达标是正常情况（候选列表按 usage 排序，末尾本来就可能不够）。
     */
    public ScanResult scan() {
        ScanResult result = new ScanResult();
        if (!properties.isSkillPromotionEnabled()) {
            return result;
        }
        List<Long> ids = skillMapper.selectPromotionCandidates(properties.getSkillPromotionMinUsage(),
                properties.getSkillPromotionLimit());
        result.setCandidates(ids.size());
        for (Long id : ids) {
            try {
                LearningSkillDO skill = skillMapper.selectById(id);
                if (skill == null || !evaluate(skill).isEligible()) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                LearningSkillDO promoted = promote(SYSTEM_ACTOR, id);
                result.getPromotedIds().add(promoted.getId());
                result.setPromoted(result.getPromoted() + 1);
            } catch (RuntimeException e) {
                result.setSkipped(result.getSkipped() + 1);
                log.warn("evt=memory.skill.promote_failed skill={} err={}", id, e.toString());
            }
        }
        log.info("evt=memory.skill.scan candidates={} promoted={} skipped={}", result.getCandidates(),
                result.getPromoted(), result.getSkipped());
        return result;
    }

    /** 门槛的唯一实现：手动晋升与每日扫描都走这里，避免两套判断。 */
    public Candidate evaluate(LearningSkillDO skill) {
        Date since = new Date(System.currentTimeMillis() - daysToMillis(properties.getSkillPromotionWindowDays()));
        int users = skillMapper.countWindowUsers(skill.getId(), since);
        int usage = skillMapper.countWindowUsage(skill.getId(), since);
        int success = skillMapper.countWindowSuccess(skill.getId(), since);
        double rate = usage == 0 ? 0.0 : (double) success / usage;

        Candidate candidate = new Candidate();
        candidate.setSkill(skill);
        candidate.setWindowUsers(users);
        candidate.setWindowUsage(usage);
        candidate.setWindowSuccess(success);
        candidate.setWindowSuccessRate(round(rate));

        Integer lifetimeUsage = skill.getUsageCount() == null ? 0 : skill.getUsageCount();
        List<String> gaps = new ArrayList<>();
        if (lifetimeUsage < properties.getSkillPromotionMinUsage()) {
            gaps.add("usage_count " + lifetimeUsage + " < " + properties.getSkillPromotionMinUsage());
        }
        if (users < properties.getSkillPromotionMinUsers()) {
            gaps.add("窗口内不同用户 " + users + " < " + properties.getSkillPromotionMinUsers());
        }
        if (usage < properties.getSkillPromotionMinWindowUsage()) {
            gaps.add("窗口内使用 " + usage + " < " + properties.getSkillPromotionMinWindowUsage());
        }
        if (rate < properties.getSkillPromotionMinSuccessRate()) {
            gaps.add("窗口成功率 " + round(rate) + " < " + properties.getSkillPromotionMinSuccessRate());
        }
        candidate.setEligible(gaps.isEmpty());
        candidate.setRejectReason(gaps.isEmpty() ? null : String.join("；", gaps));
        return candidate;
    }

    private LearningSkillDO buildPromoted(LearningSkillDO origin) {
        Date now = new Date();
        LearningSkillDO promoted = new LearningSkillDO();
        promoted.setId(snowflake.nextId());
        promoted.setOriginSkillId(origin.getId());
        promoted.setName(origin.getName());
        promoted.setScope(LearningSkillDO.SCOPE_PLATFORM);
        promoted.setOwnerUid(null);
        promoted.setStatus(LearningSkillDO.STATUS_ACTIVE);
        promoted.setContent(origin.getContent());
        promoted.setStepsJson(origin.getStepsJson());
        promoted.setTags(origin.getTags());
        promoted.setUsageCount(origin.getUsageCount() == null ? 0 : origin.getUsageCount());
        promoted.setSuccessCount(origin.getSuccessCount() == null ? 0 : origin.getSuccessCount());
        promoted.setCreatedAt(now);
        promoted.setUpdatedAt(now);
        return promoted;
    }

    private static long daysToMillis(int days) {
        return Math.max(1, days) * 24L * 60L * 60L * 1000L;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
