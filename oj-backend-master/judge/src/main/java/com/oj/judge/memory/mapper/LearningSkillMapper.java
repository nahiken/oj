package com.oj.judge.memory.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.oj.judge.memory.model.LearningSkillAuditDO;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.model.LearningSkillUsageDO;

/**
 * learning_skill / learning_skill_audit 的读写（契约 0006 §2）。
 *
 * <p>MySQL 是唯一真相：不建 ES 索引，匹配也用 SQL 规则。
 */
public interface LearningSkillMapper {

    @Insert("INSERT INTO learning_skill (id, name, scope, owner_uid, status, content, steps_json, tags, "
            + "source_episode_id, model, prompt_version, reject_reason, usage_count, success_count, "
            + "version, created_at, updated_at) VALUES (#{id}, #{name}, #{scope}, #{ownerUid}, #{status}, "
            + "#{content}, #{stepsJson,jdbcType=LONGVARCHAR}, #{tags,jdbcType=LONGVARCHAR}, #{sourceEpisodeId}, "
            + "#{model}, #{promptVersion}, #{rejectReason}, 0, 0, 1, #{createdAt}, #{updatedAt})")
    int insert(LearningSkillDO skill);

    @Select("SELECT * FROM learning_skill WHERE id = #{id}")
    LearningSkillDO selectById(@Param("id") Long id);

    /**
     * 幂等键的读侧：同一个个人 Skill 最多只能晋升出一条 PLATFORM 行。
     *
     * <p>用「查 + 唯一索引」双保险：查是为了正常路径返回已有行，
     * 唯一索引是为了两个并发审批不会各插入一条。
     */
    @Select("SELECT * FROM learning_skill WHERE origin_skill_id = #{originSkillId} AND scope = #{scope} LIMIT 1")
    LearningSkillDO selectByOrigin(@Param("originSkillId") Long originSkillId, @Param("scope") String scope);

    /**
     * 晋升：新建一条 PLATFORM 行并复制内容/步骤/证据计数（spec D18）。
     *
     * <p>`owner_uid` 恒为 NULL——平台经验不属于任何人；`usage_count`/`success_count`
     * 复制过来是为了让公共池里的排序一开始就有意义（新行的证据不会被清零）。
     */
    @Insert("INSERT INTO learning_skill (id, origin_skill_id, name, scope, owner_uid, status, content, steps_json, "
            + "tags, source_episode_id, model, prompt_version, reject_reason, usage_count, success_count, "
            + "version, created_at, updated_at) VALUES (#{id}, #{originSkillId}, #{name}, 'PLATFORM', NULL, "
            + "'ACTIVE', #{content,jdbcType=LONGVARCHAR}, #{stepsJson,jdbcType=LONGVARCHAR}, "
            + "#{tags,jdbcType=LONGVARCHAR}, NULL, NULL, NULL, NULL, #{usageCount}, #{successCount}, 1, "
            + "#{createdAt}, #{updatedAt})")
    int insertPromoted(LearningSkillDO skill);

    /**
     * 每日扫描的候选：个人、ACTIVE、终身使用量达标，且还没有晋升过。
     *
     * <p>窗口条件（≥3 个不同 uid）留在 Service 里用三条计数 SQL 判：一条 SQL 里
     * 同时做 DISTINCT 计数与成功率会把门槛规则埋进字符串，单测就测不了。
     */
    @Select("SELECT s.id FROM learning_skill s WHERE s.scope = 'PERSONAL' AND s.status = 'ACTIVE' "
            + "AND s.usage_count >= #{minUsage} "
            + "AND NOT EXISTS (SELECT 1 FROM learning_skill p WHERE p.origin_skill_id = s.id "
            + "AND p.scope = 'PLATFORM') "
            + "ORDER BY s.usage_count DESC, s.success_count DESC LIMIT #{limit}")
    List<Long> selectPromotionCandidates(@Param("minUsage") int minUsage, @Param("limit") int limit);

    @Select("SELECT COUNT(DISTINCT uid) FROM learning_skill_usage WHERE skill_id = #{skillId} "
            + "AND used_at >= #{since}")
    int countWindowUsers(@Param("skillId") Long skillId, @Param("since") Date since);

    @Select("SELECT COUNT(*) FROM learning_skill_usage WHERE skill_id = #{skillId} AND used_at >= #{since}")
    int countWindowUsage(@Param("skillId") Long skillId, @Param("since") Date since);

    @Select("SELECT COUNT(*) FROM learning_skill_usage WHERE skill_id = #{skillId} AND used_at >= #{since} "
            + "AND rewarded = 1")
    int countWindowSuccess(@Param("skillId") Long skillId, @Param("since") Date since);

    @Select("<script>SELECT * FROM learning_skill WHERE 1 = 1"
            + "<if test='status != null'> AND status = #{status}</if>"
            + "<if test='scope != null'> AND scope = #{scope}</if>"
            + " ORDER BY updated_at DESC LIMIT 100</script>")
    List<LearningSkillDO> selectList(@Param("status") String status, @Param("scope") String scope);

    @Update("UPDATE learning_skill SET status = #{status}, reject_reason = #{rejectReason}, "
            + "updated_at = #{updatedAt}, version = version + 1 WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status,
            @Param("rejectReason") String rejectReason, @Param("updatedAt") Date updatedAt);

    @Update("UPDATE learning_skill SET source_episode_id = #{episodeId}, model = #{model}, "
            + "prompt_version = #{promptVersion} WHERE id = #{id}")
    int updateProvenance(@Param("id") Long id, @Param("episodeId") Long episodeId,
            @Param("model") String model, @Param("promptVersion") String promptVersion);

    /** usage_count 只在 Skill 真正被注入 prompt 时 +1。 */
    @Update("UPDATE learning_skill SET usage_count = usage_count + 1 WHERE id = #{id}")
    int incrementUsage(@Param("id") Long id);

    @Update("UPDATE learning_skill SET success_count = success_count + 1 WHERE id = #{id}")
    int incrementSuccess(@Param("id") Long id);

    /**
     * S6 的匹配：ACTIVE 且（我的 PERSONAL 或 任意 PLATFORM）。
     * 排序在 Java 侧做（标签重合 → Pattern 类型 → 成功率），这里只负责取候选。
     */
    @Select("SELECT * FROM learning_skill WHERE status = 'ACTIVE' "
            + "AND (scope = 'PLATFORM' OR (scope = 'PERSONAL' AND owner_uid = #{uid})) "
            + "ORDER BY success_count DESC, usage_count DESC LIMIT 50")
    List<LearningSkillDO> selectUsable(@Param("uid") Long uid);

    @Select("SELECT COUNT(*) FROM learning_skill WHERE source_episode_id = #{episodeId}")
    int countBySourceEpisode(@Param("episodeId") Long episodeId);

    @Insert("INSERT INTO learning_skill_audit (id, skill_id, from_status, to_status, actor_uid, reason, created_at) "
            + "VALUES (#{id}, #{skillId}, #{fromStatus}, #{toStatus}, #{actorUid}, #{reason}, #{createdAt})")
    int insertAudit(LearningSkillAuditDO audit);

    /** 注入即记一行，AC 之后按 10 分钟窗口归因 success_count。 */
    @Insert("INSERT INTO learning_skill_usage (id, uid, problem_id, skill_id, used_at, rewarded) "
            + "VALUES (#{id}, #{uid}, #{problemId}, #{skillId}, #{usedAt}, 0)")
    int insertUsage(@Param("id") Long id, @Param("uid") Long uid, @Param("problemId") Long problemId,
            @Param("skillId") Long skillId, @Param("usedAt") Date usedAt);

    @Select("SELECT * FROM learning_skill_usage WHERE uid = #{uid} AND problem_id = #{problemId} "
            + "AND rewarded = 0 AND used_at >= #{since} ORDER BY used_at ASC")
    List<LearningSkillUsageDO> selectRewardable(@Param("uid") Long uid,
            @Param("problemId") Long problemId, @Param("since") Date since);

    @Update("UPDATE learning_skill_usage SET rewarded = 1 WHERE id = #{id} AND rewarded = 0")
    int markRewarded(@Param("id") Long id);

    /**
     * 全量重建的数据源：所有 ACTIVE 行（平台 + 所有人的个人 Skill）。
     *
     * <p>不在 SQL 里按 uid 过滤：索引要能服务所有用户，可见性由查询条件负责。
     */
    @Select("SELECT * FROM learning_skill WHERE status = 'ACTIVE' ORDER BY id")
    List<LearningSkillDO> selectActiveAll();
}
