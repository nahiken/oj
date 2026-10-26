package com.oj.judge.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.oj.judge.model.entity.Problem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
* @author OJ
* @description 针对表【problem(题目)】的数据库操作Mapper
* @createDate 2024-10-15 19:32:36
* @Entity com.oj.common.model.entity.Problem
*/
public interface ProblemMapper extends BaseMapper<Problem> {
    Long getPrevNextProblemId(@Param("id") Long id, @Param("direction") String direction);

    /**
     * 绕过逻辑删除过滤读一行：下架后 `is_delete=1`，但索引需要把这一行写成
     * `isDelete=true` 的墓碑文档，而 `selectById` 那时已经查不到它了。
     */
    @Select("SELECT * FROM problem WHERE id = #{id}")
    Problem selectByIdIgnoreLogic(@Param("id") Long id);

    /**
     * 水位兜底扫变更：同样**绕过逻辑删除**，否则漏投的「下架」事件永远补不回来
     * （`selectList` 会自动加 `is_delete=0`）。只取 `id` / `updateTime`，按时间升序。
     */
    @Select("SELECT id, update_time FROM problem WHERE update_time > #{since} "
            + "ORDER BY update_time ASC LIMIT #{limit}")
    List<Problem> selectChangedIgnoreLogic(@Param("since") Date since, @Param("limit") int limit);

    /** 全量重建读全表：**含墓碑行**，重建后索引才与 MySQL 完全一致（契约 0005 §3.3）。 */
    @Select("SELECT * FROM problem ORDER BY id")
    List<Problem> selectAllIgnoreLogic();
}
