package com.oj.judge.memory.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.hutool.json.JSONUtil;

/**
 * 事件 payload 的序列化与截断（契约 0004 §2.2）。
 *
 * <p>超限时保留所有数值字段、丢弃字符串，并置 `truncated=true` + `originalBytes`。
 * 不能整体替换：`dwell` 的 `durationMs`、`wa` 的 `judgeStatus` 一旦丢了，事件就等于没记。
 *
 * <p>纯函数：单测直接覆盖「刚好超限」「远小于上限」「只有字符串」三种边界。
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    public static String toJson(Map<String, Object> payload, int maxBytes) {
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : payload;
        String json = JSONUtil.toJsonStr(body);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (maxBytes <= 0 || bytes <= maxBytes) {
            return json;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (entry.getValue() instanceof Number) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        kept.put("truncated", true);
        kept.put("originalBytes", bytes);
        return JSONUtil.toJsonStr(kept);
    }
}
