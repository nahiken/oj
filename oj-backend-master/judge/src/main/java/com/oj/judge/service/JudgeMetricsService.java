package com.oj.judge.service;

import java.util.Map;

/**
 * 判题链路指标快照（{@code GET /judge/metrics} 的数据来源）。
 *
 * <p>组装放在 Service 层，Controller 只调用并包装 {@code R<T>}（ARCHITECTURE §分层）。
 */
public interface JudgeMetricsService {

    /**
     * 提交/判题阶段耗时、异步发布队列、老化晋升等指标快照。
     */
    Map<String, Object> snapshot();
}
