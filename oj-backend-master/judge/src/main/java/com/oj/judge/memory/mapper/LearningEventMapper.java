package com.oj.judge.memory.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.oj.judge.memory.model.LearningEventDO;
import com.oj.judge.memory.model.SessionKey;
import com.oj.judge.memory.model.TypeCount;

/**
 * learning_event 的读写。契约 0004 §3.1、§4.1。
 *
 * <p>不用 MyBatis-Plus 的 {@code save()}：`INSERT IGNORE` 是幂等语义，MP 的通用插入表达不了。
 */
public interface LearningEventMapper {

    @Insert("INSERT IGNORE INTO learning_event "
            + "(id, event_id, uid, event_type, problem_id, submit_id, source, payload, occurred_at, created_at) "
            + "VALUES (#{id}, #{eventId}, #{uid}, #{eventType}, #{problemId}, #{submitId}, #{source}, "
            + "#{payload,jdbcType=VARCHAR}, #{occurredAt}, #{createdAt})")
    int insertIgnore(LearningEventDO event);

    /**
     * 有待消费事件、且最后一条事件已经早于 `before` 的 (uid, problem_id) 分组。
     */
    @Select("SELECT uid, problem_id FROM learning_event "
            + "WHERE episode_id IS NULL AND occurred_at >= #{after} AND occurred_at <= #{before} "
            + "GROUP BY uid, problem_id ORDER BY MIN(occurred_at) ASC LIMIT #{limit}")
    List<SessionKey> selectUncoveredKeys(@Param("after") Date after,
            @Param("before") Date before,
            @Param("limit") int limit);

    /**
     * 分页读未消费事件（按 `(occurred_at, id)` 升序）。分页而不是一次 LIMIT：
     * 大会话不会被截断后伪装成两个 Episode（复审 P9b）。
     */
    @Select("SELECT * FROM learning_event "
            + "WHERE episode_id IS NULL AND uid = #{uid} AND problem_id = #{problemId} "
            + "AND (occurred_at > #{cursorTime} OR (occurred_at = #{cursorTime} AND id > #{cursorId})) "
            + "ORDER BY occurred_at ASC, id ASC LIMIT #{limit}")
    List<LearningEventDO> selectUncoveredPage(@Param("uid") Long uid,
            @Param("problemId") Long problemId,
            @Param("cursorTime") Date cursorTime,
            @Param("cursorId") long cursorId,
            @Param("limit") int limit);

    /**
     * 回填消费标记。只更新仍是 NULL 的行，重复调用不会把已归属别的 Episode 的事件抢走。
     */
    @Update("<script>UPDATE learning_event SET episode_id = #{episodeId} "
            + "WHERE episode_id IS NULL AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markEpisode(@Param("ids") List<Long> ids, @Param("episodeId") Long episodeId);

    /**
     * 放弃回看窗口外的未消费事件：`episode_id=0` 表示「已终结，不再聚合」，
     * 与 `NULL`（未消费）区分开，`events.uncovered` 因此可以归零（复审 P9a）。
     */
    @Update("UPDATE learning_event SET episode_id = 0 "
            + "WHERE episode_id IS NULL AND occurred_at < #{before} "
            + "ORDER BY occurred_at ASC LIMIT #{limit}")
    int abandonBefore(@Param("before") Date before, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM learning_event WHERE episode_id IS NULL")
    long countUncovered();

    /**
     * 失败类型分布：`payload.judgeStatus` 的取值分布，供 ERROR_FOCUS 归纳。
     *
     * <p>直接从事件算而不是在 Episode 上再加一列：状态分布属于「原始证据」，
     * Episode 只保留结论（outcome + 摘要），避免两处口径。
     */
    @Select("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.judgeStatus')) AS label, COUNT(*) AS total "
            + "FROM learning_event WHERE event_type = 'wa' AND uid = #{uid} AND occurred_at >= #{since} "
            + "GROUP BY 1")
    List<TypeCount> countWaStatus(@Param("uid") Long uid, @Param("since") Date since);

    @Select("SELECT event_type AS label, COUNT(*) AS total FROM learning_event "
            + "WHERE occurred_at >= #{since} GROUP BY event_type")
    List<TypeCount> countByType(@Param("since") Date since);

    @Select("SELECT COUNT(*) FROM learning_event")
    long countAll();
}
