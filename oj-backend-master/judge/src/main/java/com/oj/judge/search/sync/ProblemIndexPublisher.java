package com.oj.judge.search.sync;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.oj.judge.search.SearchProperties;
import com.oj.judge.search.metrics.SearchMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * 把"题目变了"变成一条进程内事件。
 *
 * <p>**绝不向调用方抛错**：题目保存成功与否不取决于索引，检索是旁路（契约 0005 §3.1）。
 */
@Component
@Slf4j
public class ProblemIndexPublisher {

    private final ApplicationEventPublisher publisher;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ProblemIndexPublisher(ApplicationEventPublisher publisher, SearchProperties properties,
            SearchMetrics metrics) {
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void publish(Long problemId, String reason) {
        if (problemId == null || !properties.isEnabled()) {
            return;
        }
        try {
            publisher.publishEvent(new ProblemIndexEvent(problemId, reason));
        } catch (RuntimeException e) {
            metrics.indexFailed(1);
            log.warn("publish problem index event failed: id={} reason={} err={}", problemId, reason, e.toString());
        }
    }
}
