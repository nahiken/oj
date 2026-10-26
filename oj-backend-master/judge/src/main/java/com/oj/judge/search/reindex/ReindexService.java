package com.oj.judge.search.reindex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

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
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 全量重建（契约 0005 §3.1 第三条通路）。
 *
 * <p>刻意"先删后建"而不是 upsert：mapping 变了、数据脏了都要靠它回到已知状态，
 * 而 upsert 会把旧字段与新 mapping 混在一起。删建之间索引是空的，本机实验环境可接受。
 *
 * <p>写入带 {@code version_type=external}（版本号取 `update_time`），因此重建完成后
 * ticket 05 的增量同步可以直接沿用同一套版本语义，不会出现"重建把新版本覆盖成旧版本"。
 *
 * <p>embedding 按批（默认 32）生成。**边车不可用不阻断重建**：文字照常进索引，
 * dense 那一路少一篇文档，结果里 `embeddingFailed` 会如实报出来。
 */
@Service
@Slf4j
public class ReindexService {

    private final ProblemMapper problemMapper;
    private final SearchIndexService indexService;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final EmbeddingClient embeddingClient;

    public ReindexService(ProblemMapper problemMapper, SearchIndexService indexService,
            SearchProperties properties, SearchMetrics metrics, EmbeddingClient embeddingClient) {
        this.problemMapper = problemMapper;
        this.indexService = indexService;
        this.properties = properties;
        this.metrics = metrics;
        this.embeddingClient = embeddingClient;
    }

    public ReindexResult reindex() {
        long started = System.nanoTime();
        ElasticsearchClient client = indexService.requireClient();

        // 含墓碑行：重建后索引才与 MySQL 全表一致，软删除不会因为一次重建而"被物理删除"
        List<Problem> problems = problemMapper.selectAllIgnoreLogic();
        indexService.recreate();

        int ok = 0;
        int failed = 0;
        int conflict = 0;
        int embedded = 0;
        int embeddingFailed = 0;
        int batchSize = Math.max(1, properties.getReindexBatchSize());
        int embedBatch = Math.max(1, properties.getEmbeddingBatchSize());

        for (int from = 0; from < problems.size(); from += batchSize) {
            List<Problem> batch = problems.subList(from, Math.min(from + batchSize, problems.size()));
            List<ProblemDocument> documents = new ArrayList<>(batch.size());
            for (Problem problem : batch) {
                documents.add(ProblemDocumentBuilder.build(problem));
            }

            List<float[]> vectors = embed(documents, embedBatch);
            if (vectors != null) {
                embedded += vectors.size();
                String modelId = embeddingClient.modelId();
                for (int i = 0; i < documents.size(); i++) {
                    documents.get(i).setEmbedding(toList(vectors.get(i)));
                    documents.get(i).setModelId(modelId);
                }
            } else {
                embeddingFailed += documents.size();
            }

            List<BulkOperation> ops = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                Problem problem = batch.get(i);
                ProblemDocument document = documents.get(i);
                long version = ProblemDocumentBuilder.versionOf(problem);
                ops.add(BulkOperation.of(op -> op.index(idx -> idx
                        .index(indexService.indexName())
                        .id(String.valueOf(problem.getId()))
                        .document(document)
                        .version(version)
                        .versionType(VersionType.External))));
            }

            BulkResponse response = bulk(client, ops);
            for (var item : response.items()) {
                var error = item.error();
                if (error == null) {
                    ok++;
                } else if (error.type() != null && error.type().contains("version_conflict")) {
                    conflict++;
                } else {
                    failed++;
                }
            }
        }

        metrics.indexOk(ok);
        metrics.indexFailed(failed);
        metrics.indexConflict(conflict);

        // 重建后立即可查：否则脚本与管理员会在 refresh_interval（默认 1s）内看到空索引，
        // 把"刚建完还没刷"误判成"索引没生效"。
        refresh(client);

        ReindexResult result = new ReindexResult();
        result.setIndex(indexService.indexName());
        result.setDocuments(problems.size());
        result.setIndexed(ok);
        result.setFailed(failed);
        result.setConflict(conflict);
        result.setEmbedded(embedded);
        result.setEmbeddingFailed(embeddingFailed);
        result.setTookMs((System.nanoTime() - started) / 1_000_000L);
        log.info("search reindex done: {}", result);
        return result;
    }

    /** @return 每篇文档的向量；边车不可用时返回 null（不是空列表，空列表会被误读成"0 篇需要向量"） */
    private List<float[]> embed(List<ProblemDocument> documents, int batchSize) {
        if (!properties.isEmbeddingEnabled()) {
            return null;
        }
        try {
            List<float[]> out = new ArrayList<>(documents.size());
            for (int from = 0; from < documents.size(); from += batchSize) {
                List<ProblemDocument> slice = documents.subList(from, Math.min(from + batchSize, documents.size()));
                List<String> passages = new ArrayList<>(slice.size());
                for (ProblemDocument document : slice) {
                    passages.add(ProblemDocumentBuilder.passageOf(document, properties.getPassageMaxChars()));
                }
                out.addAll(embeddingClient.embedPassages(passages));
            }
            return out;
        } catch (SearchException e) {
            log.warn("reindex without embeddings: {}", e.getMessage());
            return null;
        }
    }

    private static List<Float> toList(float[] values) {
        List<Float> out = new ArrayList<>(values.length);
        for (float value : values) {
            out.add(value);
        }
        return out;
    }

    private BulkResponse bulk(ElasticsearchClient client, List<BulkOperation> ops) {
        BulkRequest request = BulkRequest.of(b -> b.operations(ops));
        try {
            return client.bulk(request);
        } catch (IOException e) {
            throw indexService.unavailable(e);
        } catch (SearchException e) {
            throw e;
        }
    }

    private void refresh(ElasticsearchClient client) {
        try {
            client.indices().refresh(r -> r.index(indexService.indexName()));
        } catch (IOException e) {
            throw indexService.unavailable(e);
        }
    }

    @Data
    public static class ReindexResult {
        private String index;
        /** 读到的行数（含墓碑行；重建后索引与 MySQL 全表一致） */
        private int documents;
        private int indexed;
        private int failed;
        /** 版本冲突数：外部版本号比索引里的旧，被 ES 拒绝（契约 0005 §3.2） */
        private int conflict;
        /** 成功写入向量的文档数；边车不可用时为 0 */
        private int embedded;
        private int embeddingFailed;
        private long tookMs;
    }
}
