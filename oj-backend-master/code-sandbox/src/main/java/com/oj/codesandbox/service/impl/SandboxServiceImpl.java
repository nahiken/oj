package com.oj.codesandbox.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.codesandbox.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.model.enums.ExecuteCodeStatusEnum;
import com.oj.codesandbox.model.enums.LanguageEnum;
import com.oj.codesandbox.service.SandboxService;
import com.oj.codesandbox.template.SandboxTemplate;

@Service
public class SandboxServiceImpl implements SandboxService {
    @Resource
    private SandboxTemplate javaAcmSandbox;
    @Resource
    private SandboxTemplate cppAcmSandbox;

    @Override
    public ExecuteCodeRes execute(ExecuteCodeReq executeCodeReq) {
        List<String> inputList = executeCodeReq.getInputList();
        String code = executeCodeReq.getCode();
        LanguageEnum language = LanguageEnum.getEnumByValue(executeCodeReq.getLanguage());

        if(language == null) {
            return ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.NOT_SUPPORTED_LANGUAGE.getValue())
                    .msg(ExecuteCodeStatusEnum.NOT_SUPPORTED_LANGUAGE.getMsg())
                    .build();
        }

        switch (language) {
            case LanguageEnum.JAVA:
                return javaAcmSandbox.executeCode(inputList, code, ".java");
            case LanguageEnum.CPP:
                return cppAcmSandbox.executeCode(inputList, code, ".cpp");
            default:
                return null;
        }
    }
}
