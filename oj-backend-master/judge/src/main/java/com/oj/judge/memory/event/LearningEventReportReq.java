package com.oj.judge.memory.event;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import lombok.Data;

/**
 * 前端批量上报请求（契约 0004 §2.2）。
 *
 * <p>`eventId` 由前端生成（uuid），服务端用它做幂等；缺省时服务端补一个，
 * 但那样重发就会重复计一条，所以 SDK 必须带。
 */
@Data
public class LearningEventReportReq {

    @NotNull(message = "events 不能为空")
    @Size(min = 1, max = 20, message = "单次最多上报 20 条")
    @Valid
    private List<Item> events;

    @Data
    public static class Item {
        private String eventId;
        private String eventType;
        /** 0 / null = 无题目上下文 */
        private Long problemId;
        private Map<String, Object> payload;
        /** 客户端时间，仅作参考，不参与聚合 */
        private Long clientTime;
    }
}
