package com.oj.judge.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.vo.problemsubmit.PassCountVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * @author OJ
 * @description 针对表【problem_submit(题目提交)】的数据库操作Mapper
 * @createDate 2024-10-16 10:19:54
 * @Entity com.oj.common.model.entity.ProblemSubmit
 */
public interface ProblemSubmitMapper extends BaseMapper<ProblemSubmit> {

    PassCountVo getPassCount(@Param("userId") Long userId, @Param("difficulty") String difficulty);

    List<String> getCheckInDates(@Param("userId") Long userId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);

    /**
     * 认领：只写状态机相关列，不碰 judge_info（等待期占位 JSON 必须保留）。
     *
     * <p>`epoch <= ?` 是纵深防御：旧执行者的 epoch 小于库内值时写不进来（契约 0003 §6）。
     *
     * @return 1 = 生效，0 = 版本已推进或行不存在
     */
    @Update("UPDATE problem_submit SET status = #{status}, version = #{version}, "
            + "worker_id = #{workerId,jdbcType=VARCHAR}, attempt = #{attempt,jdbcType=INTEGER}, "
            + "epoch = #{epoch}, started_at = #{startedAt,jdbcType=TIMESTAMP} "
            + "WHERE id = #{id} AND version < #{version} AND epoch <= #{epoch}")
    int applyClaim(@Param("id") Long id,
            @Param("status") int status,
            @Param("version") int version,
            @Param("workerId") String workerId,
            @Param("attempt") Integer attempt,
            @Param("epoch") long epoch,
            @Param("startedAt") Date startedAt);

    /**
     * 回退（重试/对账重置）：同样不写 judge_info。
     */
    @Update("UPDATE problem_submit SET status = #{status}, version = #{version}, "
            + "attempt = #{attempt,jdbcType=INTEGER} "
            + "WHERE id = #{id} AND version < #{version}")
    int applyReset(@Param("id") Long id,
            @Param("status") int status,
            @Param("version") int version,
            @Param("attempt") Integer attempt);

    /**
     * 重新判题重置：除状态机列外，同时落 priority 与 enqueued_at。
     *
     * <p>不写这两列时，库里仍是旧优先级和旧入队时间，老化任务会把「正在判题」的
     * 重新判题行当成积压候选再次晋升并重投（重复消息靠租约兜底，但会放大流量）。
     */
    @Update("UPDATE problem_submit SET status = #{status}, version = #{version}, "
            + "attempt = #{attempt,jdbcType=INTEGER}, priority = #{priority}, enqueued_at = #{enqueuedAt} "
            + "WHERE id = #{id} AND version < #{version}")
    int applyRejudge(@Param("id") Long id,
            @Param("status") int status,
            @Param("version") int version,
            @Param("attempt") Integer attempt,
            @Param("priority") String priority,
            @Param("enqueuedAt") Date enqueuedAt);

    /**
     * 终态写入（带 fencing）：除了单调版本，还要求库内 epoch 不大于本次写入的 epoch。
     *
     * <p>用来挡住「拿旧 epoch、读当前 version 再写」的旧执行者（契约 0003 §5.1 第 6 步）。
     * 未带 epoch 的重载已删除（复审 P2）：它曾让死信置败成为唯一绕过 fencing 的写库路径。
     *
     * @return 1 = 生效，0 = 已写过 / 行不存在 / 被更高的 epoch 拦下
     */
    @Update("UPDATE problem_submit SET status = #{status}, version = #{version}, "
            + "worker_id = #{workerId,jdbcType=VARCHAR}, attempt = #{attempt,jdbcType=INTEGER}, "
            + "epoch = #{epoch}, judge_info = #{judgeInfo,jdbcType=LONGVARCHAR}, "
            + "started_at = #{startedAt,jdbcType=TIMESTAMP}, finished_at = #{finishedAt,jdbcType=TIMESTAMP} "
            + "WHERE id = #{id} AND version < #{version} AND epoch <= #{epoch}")
    int applyTransitionFenced(@Param("id") Long id,
            @Param("status") int status,
            @Param("version") int version,
            @Param("workerId") String workerId,
            @Param("attempt") Integer attempt,
            @Param("epoch") long epoch,
            @Param("judgeInfo") String judgeInfo,
            @Param("startedAt") Date startedAt,
            @Param("finishedAt") Date finishedAt);

    /**
     * 对账：长时间停留在 WAITING 的提交，说明消息可能没投出去。
     */
    @Select("SELECT * FROM problem_submit WHERE status = 0 AND is_delete = 0 "
            + "AND update_time < #{before} ORDER BY update_time ASC LIMIT #{limit}")
    List<ProblemSubmit> selectStaleWaiting(@Param("before") Date before, @Param("limit") int limit);

    /**
     * 对账：长时间停留在 RUNNING 的提交，说明消费者可能已失联。
     */
    @Select("SELECT * FROM problem_submit WHERE status = 1 AND is_delete = 0 "
            + "AND update_time < #{before} ORDER BY update_time ASC LIMIT #{limit}")
    List<ProblemSubmit> selectStaleRunning(@Param("before") Date before, @Param("limit") int limit);

    /**
     * 老化候选：仍在排队且等待超过阈值的提交（HIGH 已到顶，不再扫）。
     */
    @Select("SELECT id, problem_id, user_id, language, attempt, priority, enqueued_at FROM problem_submit "
            + "WHERE status = 0 AND is_delete = 0 AND priority <> 'HIGH' AND enqueued_at < #{before} "
            + "ORDER BY enqueued_at ASC LIMIT #{limit}")
    List<ProblemSubmit> selectAgingCandidates(@Param("before") Date before, @Param("limit") int limit);

    /**
     * 老化晋升：CAS 在「仍在排队 + 优先级未变」上，防止重复晋升/覆盖已被消费的行。
     *
     * @return 1 = 本次晋升生效
     */
    @Update("UPDATE problem_submit SET priority = #{to} WHERE id = #{id} AND status = 0 AND priority = #{from}")
    int updatePriority(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
