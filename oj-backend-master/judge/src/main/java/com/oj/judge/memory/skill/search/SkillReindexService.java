package com.oj.judge.memory.skill.search;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillDO;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 索引全量重建（spec D20 的兜底通路）。
 *
 * <p>先删后建：mapping 变了、双写漏了、数据脏了都靠它回到已知状态。
 * 数据源是 MySQL 的 **ACTIVE 行全量**（平台 + 所有人的个人 Skill）——
 * 「别人的个人 Skill」在查询时被 ES 的可见性条件挡掉，不是在索引里被丢掉，
 * 否则「自己的个人 Skill」也得靠额外一次写入才能搜到。
 */
@Service
@Slf4j
public class SkillReindexService {

    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private SkillIndexService indexService;
    @Resource
    private SkillSearchProperties properties;
    @Resource
    private SkillDocumentEmbedder embedder;

    public ReindexResult reindex() {
        long started = System.nanoTime();
        ElasticsearchClient client = indexService.requireClient();
        List<LearningSkillDO> skills = skillMapper.selectActiveAll();
        indexService.recreate();

        int indexed = 0;
        int failed = 0;
        int batchSize = Math.max(1, properties.getReindexBatchSize());
        for (int from = 0; from < skills.size(); from += batchSize) {
            List<LearningSkillDO> batch = skills.subList(from, Math.min(from + batchSize, skills.size()));
            List<BulkOperation> ops = new ArrayList<>(batch.size());
            for (LearningSkillDO skill : batch) {
                SkillDocument document = SkillDocumentBuilder.build(skill, properties.getContentMaxChars());
                embedder.embed(document);
                ops.add(BulkOperation.of(op -> op.index(idx -> idx
                        .index(indexService.indexName())
                        .id(String.valueOf(skill.getId()))
                        .document(document))));
            }
            BulkResponse response = bulk(client, ops);
            for (var item : response.items()) {
                if (item.error() == null) {
                    indexed++;
                } else {
                    failed++;
                    log.warn("skill reindex item failed: id={} err={}", item.id(),
                            item.error().reason());
                }
            }
        }

        // 重建后立即可查：否则脚本会在 refresh_interval 内看到空索引，把"刚建完"误判成"没生效"。
        refresh(client);

        ReindexResult result = new ReindexResult();
        result.setIndex(indexService.indexName());
        result.setDocuments(skills.size());
        result.setIndexed(indexed);
        result.setFailed(failed);
        result.setTookMs((System.nanoTime() - started) / 1_000_000L);
        log.info("skill reindex done: {}", result);
        return result;
    }

    private BulkResponse bulk(ElasticsearchClient client, List<BulkOperation> ops) {
        try {
            return client.bulk(BulkRequest.of(b -> b.operations(ops)));
        } catch (java.io.IOException e) {
            throw indexService.unavailable(e);
        }
    }

    private void refresh(ElasticsearchClient client) {
        try {
            client.indices().refresh(r -> r.index(indexService.indexName()));
        } catch (Exception e) {
            throw indexService.unavailable(e);
        }
    }

    @Data
    public static class ReindexResult {
        private String index;
        /** MySQL 里的 ACTIVE 行数 */
        private int documents;
        private int indexed;
        private int failed;
        private long tookMs;
    }
}
