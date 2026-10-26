package com.oj.judge.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 题目提交
 * @TableName problem_submit
 */
@TableName(value ="problem_submit")
@Data
public class ProblemSubmit {
    /**
     * 题目提交id
     */
    @TableId
    private Long id;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 用户代码
     */
    private String code;

    /**
     * 判题信息(json 对象)
     */
    private String judgeInfo;

    /**
     * 判题状态(0 - 待判题、1 - 判题中、2 - 成功、3 - 失败)
     */
    private Integer status;

    /**
     * 判题优先级 HIGH / NORMAL / RUN（P2 分级调度，老化会晋升）
     */
    private String priority;

    /**
     * 题目 id
     */
    private Long problemId;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Integer isDelete;

    /**
     * 状态版本号，与 Redis version 对齐，单调递增
     */
    private Integer version;

    /**
     * 处理该提交的 Worker 标识
     */
    private String workerId;

    /**
     * 已投递次数
     */
    private Integer attempt;

    /**
     * fencing token：每次认领推进，写结果时校验（P3）
     */
    private Long epoch;

    /**
     * 首次入队时间
     */
    private Date enqueuedAt;

    /**
     * 开始判题时间
     */
    private Date startedAt;

    /**
     * 判题结束时间
     */
    private Date finishedAt;
}