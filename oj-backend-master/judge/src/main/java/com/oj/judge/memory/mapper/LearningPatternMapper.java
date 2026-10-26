package com.oj.judge.memory.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.oj.judge.memory.model.LearningPatternDO;

/**
 * learning_pattern 的读写。契约 0004 §5。
 */
public interface LearningPatternMapper {

    /**
     * 每用户每类型一行：重算即覆盖描述/证据/置信度并 version+1。
     */
    @Insert("INSERT INTO learning_pattern "
            + "(id, uid, pattern_type, description, evidence, confidence, version, created_at, updated_at) "
            + "VALUES (#{id}, #{uid}, #{patternType}, #{description}, #{evidence,jdbcType=VARCHAR}, "
            + "#{confidence}, #{version}, #{createdAt}, #{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE description = VALUES(description), evidence = VALUES(evidence), "
            + "confidence = VALUES(confidence), version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3)")
    int upsert(LearningPatternDO pattern);

    /** 只取 30 天窗口内、被最近一次重算刷新过的结论（复审 P6）。 */
    @Select("SELECT * FROM learning_pattern WHERE uid = #{uid} AND updated_at >= #{since} "
            + "ORDER BY confidence DESC, updated_at DESC LIMIT #{limit}")
    List<LearningPatternDO> selectTop(@Param("uid") Long uid,
            @Param("since") Date since,
            @Param("limit") int limit);

    @Select("SELECT * FROM learning_pattern WHERE uid = #{uid}")
    List<LearningPatternDO> selectByUid(@Param("uid") Long uid);

    /**
     * 重算后删掉本轮不再成立的类型：否则过期结论会一直挂在用户画像上（复审 P6）。
     *
     * <p>`olderThan` 是防误删的护栏：一次临时失败（比如标签查询抖动）不该把上一轮还在的结论删掉，
     * 只有当这一行连续两个重算周期都没被刷新时才判定「真的不成立了」。
     */
    @Delete("<script>DELETE FROM learning_pattern WHERE uid = #{uid} AND pattern_type NOT IN "
            + "<foreach collection='types' item='type' open='(' separator=',' close=')'>#{type}</foreach>"
            + " AND updated_at &lt; #{olderThan}</script>")
    int deleteTypesNotIn(@Param("uid") Long uid, @Param("types") List<String> types,
            @Param("olderThan") Date olderThan);

    @Delete("DELETE FROM learning_pattern WHERE uid = #{uid}")
    int deleteByUid(@Param("uid") Long uid);

    /**
     * 本轮一条 Pattern 都没产出时的过期删除：`NOT IN ()` 不是合法 SQL，所以单独一条。
     */
    @Delete("DELETE FROM learning_pattern WHERE uid = #{uid} AND updated_at &lt; #{olderThan}")
    int deleteStale(@Param("uid") Long uid, @Param("olderThan") Date olderThan);

    @Select("SELECT COUNT(*) FROM learning_pattern")
    long countAll();
}
