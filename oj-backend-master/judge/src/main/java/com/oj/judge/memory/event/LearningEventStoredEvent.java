package com.oj.judge.memory.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 事件落库成功的进程内事件：只用于触发「会话结束 → 立即物化」的快速通道。
 *
 * <p>同步监听（默认），保证 `ac` / `dwell` 落库后同一条线程上就把会话关闭，实测延迟 0s。
 */
@Data
@AllArgsConstructor
public class LearningEventStoredEvent {

    private Long uid;
    private Long problemId;
}
