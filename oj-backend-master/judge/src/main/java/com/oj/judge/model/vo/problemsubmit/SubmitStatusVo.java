package com.oj.judge.model.vo.problemsubmit;

import java.util.Date;

import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import cn.hutool.json.JSONUtil;
import lombok.Data;

/**
 * 轮询专用提交状态，不返回代码等大字段。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §9.2。
 */
@Data
public class SubmitStatusVo {

    /** 雪花 ID 超出 JS 安全整数，按字符串返回 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Integer status;

    private JudgeInfo judgeInfo;

    private Integer version;

    private Date updateTime;

    public static SubmitStatusVo of(ProblemSubmit submit) {
        if (submit == null) {
            return null;
        }
        SubmitStatusVo vo = new SubmitStatusVo();
        vo.setId(submit.getId());
        vo.setStatus(submit.getStatus() == null ? ProblemSubmitStatusEnum.WAITING.getValue() : submit.getStatus());
        vo.setJudgeInfo(parseJudgeInfo(submit.getJudgeInfo()));
        vo.setVersion(submit.getVersion());
        vo.setUpdateTime(submit.getUpdateTime());
        return vo;
    }

    public static JudgeInfo parseJudgeInfo(String json) {
        if (json == null || json.isBlank()) {
            return JudgeInfo.pending(0, "排队中");
        }
        try {
            return JSONUtil.toBean(json, JudgeInfo.class);
        } catch (Exception e) {
            return JudgeInfo.pending(0, "排队中");
        }
    }
}
