package com.oj.judge.codesandbox.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oj.codesandbox.sdk.client.CodeSandboxClient;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.codesandbox.CodeSandbox;

import lombok.extern.slf4j.Slf4j;

/**
 * 远程代码沙箱（实际调用接口的沙箱）
 */
@Service
@Slf4j
public class RemoteCodeSandbox implements CodeSandbox {
    @Value("${oj.code-sandbox.gateway-host:#{null}}")
    private String gatewayHost;

    @Override
    public ExecuteCodeRes executeCode(ExecuteCodeReq executeCodeRequest, String accessKey, String secretKey) {
        // 不要打印 secretKey：沙箱签名密钥只用于请求头
        CodeSandboxClient client;
        client = new CodeSandboxClient(accessKey, secretKey, gatewayHost);
        return client.executeCode(executeCodeRequest);
    }
}
