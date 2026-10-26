package com.oj.judge.memory.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事件信封（契约 0004 §2.2）：MQ 消息体与落库前的最小传输单元。
 *
 * <p>`occurredAt` 一律由服务端赋值（接收时刻），客户端时间只放在 payload 里作参考。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningEventMessage {

    public static final String SOURCE_SERVER = "SERVER";
    public static final String SOURCE_CLIENT = "CLIENT";

    private String eventId;
    private Long uid;
    private String eventType;
    /** 0 = 无题目上下文 */
    private Long problemId;
    /** 0 = 非提交事件 */
    private Long submitId;
    private String source;
    /** 已序列化的 JSON，上限由 payloadMaxBytes 控制 */
    private String payload;
    private Long occurredAt;
}
