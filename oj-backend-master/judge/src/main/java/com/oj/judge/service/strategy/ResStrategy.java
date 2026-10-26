package com.oj.judge.service.strategy;

import java.util.List;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.model.dto.problem.JudgeConfig;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;

public interface ResStrategy {
    JudgeInfo processExecuteCodeRes(ExecuteCodeRes executeCodeRes, List<String> inputList, List<String> expectedOutputList, JudgeConfig judgeConfig);    
}
