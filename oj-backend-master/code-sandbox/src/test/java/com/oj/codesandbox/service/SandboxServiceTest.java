package com.oj.codesandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.oj.codesandbox.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.model.enums.ExecuteCodeStatusEnum;

import cn.hutool.core.io.resource.ResourceUtil;

/**
 * 端到端：走 SandboxTemplate → 借池槽位 → 本机 javac/java 跑样例代码。
 *
 * <p>钉死 native 后端：默认 application.yml 的 docker 配置依赖本机镜像与 daemon，
 * 单测不该被它们卡住；容器后端的真实验证在 {@code benchmark/run-p2.ps1 -Scenario pool-docker}。
 */
@SpringBootTest(properties = {
        "oj.code-sandbox.type=native",
        "oj.sandbox.pool.enabled=true",
        "oj.sandbox.pool.size=2"
})
public class SandboxServiceTest {

    @Resource
    private SandboxService sandboxService;

    @Test
    void testLocalJudge() {
        ExecuteCodeReq executeCodeRequest = new ExecuteCodeReq();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "3 4", "5 6"));
        String code = ResourceUtil.readStr("testCode/addNumber/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("Java");

        ExecuteCodeRes response = sandboxService.execute(executeCodeRequest);

        assertNotNull(response);
        assertEquals(ExecuteCodeStatusEnum.SUCCESS.getValue(), response.getCode(), "A+B 样例应判成功: " + response);
        assertEquals(3, response.getResults().size(), "每个输入用例都应有结果");
        assertEquals("3", response.getResults().get(0).getStdout().trim());
        assertEquals("7", response.getResults().get(1).getStdout().trim());
        assertEquals("11", response.getResults().get(2).getStdout().trim());
    }
}
