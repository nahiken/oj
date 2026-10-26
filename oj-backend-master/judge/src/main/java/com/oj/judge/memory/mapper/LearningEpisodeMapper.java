package com.oj.judge.memory.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.oj.judge.memory.model.LearningEpisodeDO;

/**
 * learning_episode 的读写。契约 0004 §4.2。
 */
public interface LearningEpisodeMapper {

    /**
     * 幂等物化：唯一键 (uid, problem_id, started_at) 命中时什么都不改（Episode 不可变），
     * 但仍返回 1——调用方据此把事件标记为已消费。
     */
    @Insert("INSERT INTO learning_episode "
            + "(id, uid, problem_id, started_at, ended_at, outcome, attempt_count, ac_count, run_count, "
            + "solution_views, copy_count, dwell_ms, event_count, truncated, summary, summary_source, "
            + "summary_version, created_at) "
            + "VALUES (#{id}, #{uid}, #{problemId}, #{startedAt}, #{endedAt}, #{outcome}, #{attemptCount}, "
            + "#{acCount}, #{runCount}, #{solutionViews}, #{copyCount}, #{dwellMs}, #{eventCount}, "
            + "#{truncated}, #{summary,jdbcType=LONGVARCHAR}, #{summarySource}, #{summaryVersion}, #{createdAt}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnore(LearningEpisodeDO episode);

    @Select("SELECT * FROM learning_episode WHERE uid = #{uid} AND problem_id = #{problemId} "
            + "AND started_at = #{startedAt}")
    LearningEpisodeDO selectByKey(@Param("uid") Long uid,
            @Param("problemId") Long problemId,
            @Param("startedAt") Date startedAt);

    @Select("SELECT * FROM learning_episode WHERE uid = #{uid} ORDER BY started_at DESC LIMIT #{limit}")
    List<LearningEpisodeDO> selectRecent(@Param("uid") Long uid, @Param("limit") int limit);

    @Select("SELECT * FROM learning_episode WHERE id = #{id}")
    LearningEpisodeDO selectById(@Param("id") Long id);

    /**
     * 可抽取的 Episode：自己做出来、没看题解、尝试不多、事件够多，且尚未产出过 Skill。
     * 这是「候选来自自己做出来的题」的唯一入口（契约 0006 §8）。
     */
    @Select("SELECT * FROM learning_episode e WHERE e.outcome = 'AC' AND e.attempt_count <= 3 "
            + "AND e.solution_views = 0 AND e.event_count >= 5 "
            + "AND NOT EXISTS (SELECT 1 FROM learning_skill s WHERE s.source_episode_id = e.id) "
            + "ORDER BY e.created_at DESC LIMIT #{limit}")
    List<LearningEpisodeDO> selectExtractionCandidates(@Param("limit") int limit);

    @Select("SELECT * FROM learning_episode WHERE uid = #{uid} AND started_at >= #{since} "
            + "ORDER BY started_at DESC LIMIT #{limit}")
    List<LearningEpisodeDO> selectSince(@Param("uid") Long uid,
            @Param("since") Date since,
            @Param("limit") int limit);

    /** 延迟口径：created_at - ended_at（近似于「会话结束到可读」） */
    @Select("SELECT id, uid, problem_id, started_at, ended_at, outcome, created_at "
            + "FROM learning_episode ORDER BY created_at DESC LIMIT #{limit}")
    List<LearningEpisodeDO> selectRecentForDelay(@Param("limit") int limit);

    @Select("SELECT DISTINCT uid FROM learning_episode WHERE started_at >= #{since} LIMIT #{limit}")
    List<Long> selectActiveUids(@Param("since") Date since, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM learning_episode")
    long countAll();

    @Select("SELECT COUNT(*) FROM learning_episode WHERE uid = #{uid} AND started_at >= #{since}")
    long countSince(@Param("uid") Long uid, @Param("since") Date since);
}
