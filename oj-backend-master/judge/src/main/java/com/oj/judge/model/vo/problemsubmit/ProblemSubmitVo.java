package com.oj.judge.model.vo.problemsubmit;

import java.util.Date;

import org.springframework.beans.BeanUtils;

import com.oj.judge.model.entity.ProblemSubmit;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import cn.hutool.json.JSONUtil;
import lombok.Data;

/**
 * 题目提交封装类
 * @TableName question
 */
@Data
public class ProblemSubmitVo {
    /**
     * 雪花 ID 超出 JS 安全整数（2^53），必须按字符串返回，否则前端会精度丢失后拿错 ID 轮询
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String language;
    private String code;

    /**
     * 判题信息
     */
    private JudgeInfo judgeInfo;

    /**
     * 判题状态（0 - 待判题、1 - 判题中、2 - 成功、3 - 失败）
     */
    private Integer status;

    private Long problemId;
    private Long userId;
    private Date createTime;
    private Date updateTime;

    /**
     * 对象转包装类
     * @param problemSubmit
     * @return
     */
    public static ProblemSubmitVo objToVo(ProblemSubmit problemSubmit) {
        if (problemSubmit == null) {
            return null;
        }
        ProblemSubmitVo problemSubmitVO = new ProblemSubmitVo();
        BeanUtils.copyProperties(problemSubmit, problemSubmitVO);
        problemSubmitVO.setJudgeInfo(JSONUtil.toBean(problemSubmit.getJudgeInfo(), JudgeInfo.class));
        return problemSubmitVO;
    }
}