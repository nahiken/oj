package com.oj.codesandbox;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.oj.codesandbox.sdk.client.CodeSandboxClient;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;

import cn.hutool.core.io.resource.ResourceUtil;

@SpringBootTest
public class SdkTest {
    @Resource
    private CodeSandboxClient codeSandboxClient;

    @Test
    public void testSdk() {
        ExecuteCodeReq executeCodeRequest = new ExecuteCodeReq();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "3 4", "5 6"));
        String code = ResourceUtil.readStr("testCode/addNumber/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("Java");

        ExecuteCodeRes response = codeSandboxClient.executeCode(executeCodeRequest);
        System.out.println(response);
    }
}
