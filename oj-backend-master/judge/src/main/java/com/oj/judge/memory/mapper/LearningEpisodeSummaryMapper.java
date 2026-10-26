package com.oj.judge.memory.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.oj.judge.memory.model.LearningEpisodeSummaryDO;

/** learning_episode_summary 的读写（ADR 0005）。 */
public interface LearningEpisodeSummaryMapper {

    @Insert("INSERT INTO learning_episode_summary (id, episode_id, source, model, prompt_version, content, "
            + "token_estimate, created_at) VALUES (#{id}, #{episodeId}, #{source}, #{model}, #{promptVersion}, "
            + "#{content,jdbcType=LONGVARCHAR}, #{tokenEstimate}, #{createdAt}) "
            + "ON DUPLICATE KEY UPDATE content = content")
    int insertIgnore(LearningEpisodeSummaryDO summary);

    @Select("SELECT * FROM learning_episode_summary WHERE episode_id = #{episodeId} AND source = 'LLM' "
            + "AND prompt_version = #{promptVersion} LIMIT 1")
    LearningEpisodeSummaryDO selectLlm(@Param("episodeId") Long episodeId,
            @Param("promptVersion") String promptVersion);

    /** 同一 prompt_version 下还没有 LLM 摘要的 Episode。 */
    @Select("SELECT e.id FROM learning_episode e WHERE NOT EXISTS ("
            + "SELECT 1 FROM learning_episode_summary s WHERE s.episode_id = e.id "
            + "AND s.source = 'LLM' AND s.prompt_version = #{promptVersion}) "
            + "ORDER BY e.created_at DESC LIMIT #{limit}")
    List<Long> selectMissingLlm(@Param("promptVersion") String promptVersion, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM learning_episode_summary WHERE source = 'LLM' AND prompt_version = #{promptVersion}")
    long countLlm(@Param("promptVersion") String promptVersion);
}
