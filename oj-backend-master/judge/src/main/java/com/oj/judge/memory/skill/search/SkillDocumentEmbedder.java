package com.oj.judge.memory.skill.search;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.oj.judge.search.SearchException;
import com.oj.judge.search.SearchProperties;
import com.oj.judge.search.embed.EmbeddingClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 给一条 Skill 文档补向量（单写与全量重建共用）。
 *
 * <p>边车不可用就写「只有文字」的文档：BM25 照常，dense 少一篇。**不抛异常**——
 * 一条文档没有向量不该让整批重建失败。
 */
@Component
@Slf4j
public class SkillDocumentEmbedder {

    private final EmbeddingClient embeddingClient;
    private final SearchProperties searchProperties;

    public SkillDocumentEmbedder(EmbeddingClient embeddingClient, SearchProperties searchProperties) {
        this.embeddingClient = embeddingClient;
        this.searchProperties = searchProperties;
    }

    public void embed(SkillDocument document) {
        if (document == null || !searchProperties.isEmbeddingEnabled()) {
            return;
        }
        try {
            String passage = SkillDocumentBuilder.passageOf(document);
            float[] vector = embeddingClient.embedPassages(List.of(passage)).get(0);
            List<Float> values = new ArrayList<>(vector.length);
            for (float value : vector) {
                values.add(value);
            }
            document.setEmbedding(values);
        } catch (RuntimeException e) {
            log.warn("skill index without embedding: id={} err={}", document.getSkillId(), e.toString());
        }
    }
}
