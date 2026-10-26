package com.oj.judge.codesandbox.impl;

import org.springframework.stereotype.Service;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.codesandbox.CodeSandbox;

/**
 * 第三方代码沙箱（调用网上现成的代码沙箱）
 */
@Service
public class ThirdPartyCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteCodeRes executeCode(ExecuteCodeReq executeCodeRequest, String accessKey, String secretKey) {
        System.out.println("第三方代码沙箱");
        return null;
    }
}
