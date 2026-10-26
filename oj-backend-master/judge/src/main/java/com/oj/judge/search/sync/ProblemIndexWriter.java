package com.oj.judge.search.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.SearchProperties;
import com.oj.judge.search.embed.EmbeddingClient;
import com.oj.judge.search.index.ProblemDocument;
import com.oj.judge.search.index.ProblemDocumentBuilder;
import com.oj.judge.search.index.SearchIndexService;
import com.oj.judge.search.metrics.SearchMetrics;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.elasticsearch.client.ResponseException;

import co.elastic.clients.elasticsearch._types.VersionType;
import lombok.extern.slf4j.Slf4j;

/**
 * 单题写入 ES：事件通路的消费者，也被水位兜底复用。
 *
 * <p>三条规则：
 * <ul>
 *   <li>**重读数据库**而不是用事件里的快照，保证写进去的是最新状态；</li>
 *   <li>`version_type=external`（版本号 = `update_time`），并发/乱序时旧写入被 ES 拒绝（409）；</li>
 *   <li>失败只计数，不向上抛——调用方是异步任务，抛了也没人接。</li>
 * </ul>
 */
@Component
@Slf4j
public class ProblemIndexWriter {

    private final ProblemMapper problemMapper;
    private final SearchIndexService indexService;
    private final SearchMetrics metrics;
    private final EmbeddingClient embeddingClient;
    private final SearchProperties properties;

    public ProblemIndexWriter(ProblemMapper problemMapper, SearchIndexService indexService,
            SearchMetrics metrics, EmbeddingClient embeddingClient, SearchProperties properties) {
        this.problemMapper = problemMapper;
        this.indexService = indexService;
        this.metrics = metrics;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    @Async
    @EventListener
    public void on(ProblemIndexEvent event) {
        index(event.getProblemId(), event.getReason());
    }

    /**
     * @return true 表示索引已处于安全状态（写入成功，或 ES 里已有更新版本）；
     *         false 表示这次没写成，调用方（水位任务）应停在原地重试。
     */
    public boolean index(Long problemId, String reason) {
        if (problemId == null || !indexService.available()) {
            return false;
        }
        // 必须绕过逻辑删除过滤：下架后这一行的 is_delete=1，`selectById` 会返回 null，
        // 而我们要的正是"把墓碑写进索引"。
        Problem problem = problemMapper.selectByIdIgnoreLogic(problemId);
        if (problem == null) {
            // 物理删除：本项目的删除是逻辑删除，走不到这里；真删了就不该再有文档
            return false;
        }
        ProblemDocument document = ProblemDocumentBuilder.build(problem);
        embed(document);
        long version = ProblemDocumentBuilder.versionOf(problem);
        try {
            ElasticsearchClient client = indexService.requireClient();
            client.index(i -> i
                    .index(indexService.indexName())
                    .id(String.valueOf(problemId))
                    .document(document)
                    .version(version)
                    .versionType(VersionType.External));
            metrics.indexOk(1);
            return true;
        } catch (Exception e) {
            if (isConflict(e)) {
                // 同版本重复写（水位回扫到刚写过的那一行）也是 409，这是正常现象，不是故障
                metrics.indexConflict(1);
                log.debug("index conflict (version not newer): id={} version={}", problemId, version);
                // 409 说明索引里已有更新版本，状态是安全的；水位可以越过这一行
                return true;
            } else {
                metrics.indexFailed(1);
                log.warn("index failed: id={} reason={} err={}", problemId, reason, e.toString());
            }
            return false;
        }
    }

    /**
     * 单篇补向量。边车不可用就退化成"只有文字"的文档：BM25 照常，dense 少一篇。
     * 注意整篇文档会被替换，所以每次单篇写入都必须重新带向量，否则编辑会把向量丢掉。
     */
    private void embed(ProblemDocument document) {
        if (!properties.isEmbeddingEnabled()) {
            return;
        }
        try {
            String passage = ProblemDocumentBuilder.passageOf(document, properties.getPassageMaxChars());
            float[] vector = embeddingClient.embedPassages(List.of(passage)).get(0);
            List<Float> values = new ArrayList<>(vector.length);
            for (float value : vector) {
                values.add(value);
            }
            document.setEmbedding(values);
            document.setModelId(embeddingClient.modelId());
        } catch (SearchException e) {
            log.warn("index without embedding: id={} err={}", document.getProblemId(), e.getMessage());
        }
    }

    /**
     * 409 可能以三种形态出现：typed client 的 {@link ElasticsearchException}、
     * 低层 {@code ResponseException}、或异常链里的版本冲突文本。三种都要认出来，
     * 否则"水位把刚写过的一行又扫一遍"会被记成索引故障。
     */
    private boolean isConflict(Exception e) {
        if (e instanceof ElasticsearchException typed) {
            if (typed.status() == 409) {
                return true;
            }
            if (typed.error() != null && "version_conflict_engine_exception".equals(typed.error().type())) {
                return true;
            }
        }
        if (e instanceof ResponseException response) {
            try {
                if (response.getResponse().getStatusLine().getStatusCode() == 409) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 拿不到状态行就退回文本判断
            }
        }
        String message = e.getMessage();
        return message != null && message.contains("version_conflict_engine_exception");
    }
}
