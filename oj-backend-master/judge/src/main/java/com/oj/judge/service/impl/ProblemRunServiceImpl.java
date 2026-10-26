package com.oj.judge.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.common.auth.utils.TokenUtils;
import com.oj.judge.codesandbox.CodeSandbox;
import com.oj.judge.codesandbox.CodeSandboxFactory;
import com.oj.judge.memory.event.LearningEventRecorder;
import com.oj.judge.memory.event.LearningEventType;
import com.oj.judge.model.dto.problem.ProblemRunReq;
import com.oj.judge.service.ProblemRunService;
import com.oj.user.api.dto.SecretDTO;
import com.oj.user.api.service.UserInnerService;

@Service
public class ProblemRunServiceImpl implements ProblemRunService {
    @Resource
    private CodeSandboxFactory codeSandboxFactory;
    @Value("${oj.code-sandbox.type:remote}")
    private String type;
    @Resource
    private LearningEventRecorder memoryRecorder;
    @DubboReference
    private UserInnerService userInnerService;

    @Override
    public ExecuteCodeRes doProblemRun(ProblemRunReq request) {
        CodeSandbox codeSandbox = codeSandboxFactory.newInstance(type);
        // 沙箱契约保持通用：problemId 留在 judge 侧，不随执行请求下发
        ExecuteCodeReq executeCodeRequest = ExecuteCodeReq.builder()
                .code(request.getCode())
                .language(request.getLanguage())
                .inputList(request.getInputList())
                .build();

        Long userId = TokenUtils.getCurrentUid();
        SecretDTO secretDTO = userInnerService.getSecretByUid(userId);
        ExecuteCodeRes response = codeSandbox.executeCode(executeCodeRequest, secretDTO.getSecretId(),
                secretDTO.getSecretKey());
        recordRun(userId, request, response);
        return response;
    }

    private void recordRun(Long userId, ProblemRunReq request, ExecuteCodeRes response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", request.getLanguage());
        payload.put("inputCount", request.getInputList() == null ? 0 : request.getInputList().size());
        if (response != null && response.getCode() != null) {
            // 契约 0004 §2.1：字段名是 status（沙箱返回的 code 即执行状态）
            payload.put("status", response.getCode());
        }
        memoryRecorder.recordForProblem(LearningEventType.RUN, userId, request.getProblemId(), payload);
    }
}
