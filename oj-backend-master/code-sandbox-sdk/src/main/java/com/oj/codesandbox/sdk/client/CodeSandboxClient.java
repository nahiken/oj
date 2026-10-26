package com.oj.codesandbox.sdk.client;

import com.oj.codesandbox.sdk.constant.UrlConstant;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.sdk.utils.SignUtils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author OJ
 * @date 2023/9/3 8:16
 * @description 调用代码沙箱的客户端
 */
@Slf4j
public class CodeSandboxClient {
    private String accessKey;
    private String secretKey;
    private String gatewayHost;

    public CodeSandboxClient(String accessKey, String secretKey, String gatewayHost) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        if (gatewayHost != null) {
            this.gatewayHost = gatewayHost;
        } else {
            this.gatewayHost = UrlConstant.GATEWAY_HOST;
        }
    }

    public ExecuteCodeRes executeCode(ExecuteCodeReq executeCodeRequest) {
        String requestBodyJson = JSONUtil.toJsonStr(executeCodeRequest);
        try (HttpResponse response = HttpRequest.post(gatewayHost + "/api/sandbox/execute")
                .header("Content-Type", "application/json")
                .header("accessKey", accessKey)
                .header("sign", SignUtils.genSign(requestBodyJson, secretKey))
                .body(requestBodyJson)
                .execute()) {
            String responseBody = response.body();
            // 响应体里有用户程序 stdout，info 级别打印会在高并发判题时淹没日志
            log.debug("sandbox response status={} bytes={}", response.getStatus(),
                    responseBody == null ? 0 : responseBody.length());
            return JSONUtil.toBean(responseBody, ExecuteCodeRes.class);
        } catch (Exception e) {
            log.warn("evt=sandbox.call.failed", e);
            return null;
        }
    }
}
