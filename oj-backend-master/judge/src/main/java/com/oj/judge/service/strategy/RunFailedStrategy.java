package com.oj.judge.service.strategy;

import java.util.List;

import org.springframework.stereotype.Component;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.model.dto.problem.JudgeConfig;
import com.oj.judge.model.enums.JudgeInfoEnum;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;

@Component
public class RunFailedStrategy implements ResStrategy {

    @Override
    public JudgeInfo processExecuteCodeRes(ExecuteCodeRes executeCodeRes, List<String> inputList,
            List<String> expectedOutputList, JudgeConfig judgeConfig) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTotal(inputList.size());

        int pass = executeCodeRes.getResults().size() - 1;
        judgeInfo.setPass(pass);
        // 最后执行的输入
        judgeInfo.setInput(inputList.get(pass));

        judgeInfo.setStatus(JudgeInfoEnum.RUNTIME_ERROR.getValue());
        judgeInfo.setMsg(executeCodeRes.getMsg());

        return judgeInfo;
    }

}
