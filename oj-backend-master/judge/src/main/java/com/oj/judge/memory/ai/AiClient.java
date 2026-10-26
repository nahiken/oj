package com.oj.judge.memory.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * ai 的唯一 HTTP 适配层（ARCHITECTURE 的 "AI Client"）。
 *
 * <p>为什么要有这一层：Service 直接 `HttpUtil.post` 会把 base-url、超时、异常语义
 * 复制到每个调用点；换地址或加鉴权时要改 N 处。/internal/* 只在本机可达，
 * 所以这里没有鉴权，只有地址与超时。
 */
@Component
public class AiClient {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8026";

    @Value("${oj.ai.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${oj.ai.timeout-ms:20000}")
    private int timeoutMs = 20000;

    /** 文本进、JSON 出。网络或解析失败一律抛给调用方，由它决定降级。 */
    public JSONObject post(String path, JSONObject payload) {
        String raw = HttpUtil.post(baseUrl + path, payload.toString(), timeoutMs);
        JSONObject json = JSONUtil.parseObj(raw);
        if (json == null || json.isEmpty()) {
            throw new IllegalStateException("ai returned an empty body for " + path);
        }
        return json;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
