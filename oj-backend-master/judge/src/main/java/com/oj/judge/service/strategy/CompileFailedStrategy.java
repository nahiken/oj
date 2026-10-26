package com.oj.judge.service.strategy;

import java.util.List;

import org.springframework.stereotype.Component;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.model.dto.problem.JudgeConfig;
import com.oj.judge.model.enums.JudgeInfoEnum;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;

/*
 * 处理编译失败的响应
 */
@Component
public class CompileFailedStrategy implements ResStrategy {
    @Override
    public JudgeInfo processExecuteCodeRes(ExecuteCodeRes executeCodeRes, List<String> inputList,
            List<String> expectedOutputList, JudgeConfig judgeConfig) {
        JudgeInfo judgeInfo = new JudgeInfo();

        judgeInfo.setTotal(inputList.size());
        judgeInfo.setPass(0);
        judgeInfo.setStatus(JudgeInfoEnum.COMPILE_ERROR.getValue());
        judgeInfo.setMsg(executeCodeRes.getMsg());

        return judgeInfo;
    }

}
