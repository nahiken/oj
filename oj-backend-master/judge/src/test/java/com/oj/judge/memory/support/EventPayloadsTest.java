package com.oj.judge.memory.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 2KB 截断必须「留数丢串」：丢字段等于丢事件（复审 P12）。
 */
class EventPayloadsTest {

    @Test
    void underLimitIsUntouched() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", 65000);

        assertEquals("{\"durationMs\":65000}", EventPayloads.toJson(payload, 2048));
    }

    @Test
    void oversizeKeepsNumbersAndMarksTruncated() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", 65000);
        payload.put("language", repeat('x', 4096));

        JSONObject json = JSONUtil.parseObj(EventPayloads.toJson(payload, 2048));

        assertEquals(65000L, json.getLong("durationMs"));
        assertEquals(true, json.getBool("truncated"));
        assertTrue(json.getInt("originalBytes") > 2048);
        assertFalse(json.containsKey("language"), "字符串字段应在超限时丢弃");
    }

    @Test
    void stringOnlyPayloadStillProducesMarker() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", repeat('x', 4096));

        JSONObject json = JSONUtil.parseObj(EventPayloads.toJson(payload, 2048));

        assertEquals(true, json.getBool("truncated"));
        assertFalse(json.containsKey("language"));
    }

    private String repeat(char value, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
