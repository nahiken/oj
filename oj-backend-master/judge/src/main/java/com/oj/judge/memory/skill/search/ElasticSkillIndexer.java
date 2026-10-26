package com.oj.judge.memory.skill.search;

import org.springframework.stereotype.Component;

import com.oj.judge.memory.model.LearningSkillDO;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import lombok.extern.slf4j.Slf4j;

/**
 * 双写实现：审核通过就写、下架就删（spec D20）。
 *
 * <p>三条不变式：
 * <ul>
 *   <li>**失败不上抛**——索引写失败不能让状态转移回滚，MySQL 才是真相，可重建；</li>
 *   <li>`refresh=wait_for`——「晋升/批准后立刻能搜到」是验收项，不能等 1s 的默认刷新；</li>
 *   <li>只留 ACTIVE 文档——非 ACTIVE 的 Skill 进来就先删掉旧文档。</li>
 * </ul>
 */
@Component
@Slf4j
public class ElasticSkillIndexer implements SkillIndexer {

    private final SkillIndexService indexService;
    private final SkillSearchProperties properties;
    private final SkillDocumentEmbedder embedder;

    public ElasticSkillIndexer(SkillIndexService indexService, SkillSearchProperties properties,
            SkillDocumentEmbedder embedder) {
        this.indexService = indexService;
        this.properties = properties;
        this.embedder = embedder;
    }

    @Override
    public void index(LearningSkillDO skill) {
        if (skill == null || skill.getId() == null || !indexService.available()) {
            return;
        }
        if (!LearningSkillDO.STATUS_ACTIVE.equals(skill.getStatus())) {
            delete(skill.getId());
            return;
        }
        SkillDocument document = SkillDocumentBuilder.build(skill, properties.getContentMaxChars());
        embedder.embed(document);
        try {
            write(document);
        } catch (Exception e) {
            // Fresh ES / 索引被删：先建再写一次。不建的话「批准了但搜不到」会静默发生，
            // 直到下一个管理员想起来跑全量重建。
            if (isIndexMissing(e)) {
                try {
                    indexService.ensureIndex();
                    write(document);
                    return;
                } catch (Exception retry) {
                    e = retry;
                }
            }
            log.warn("skill index failed: id={} err={}", skill.getId(), e.toString());
        }
    }

    private void write(SkillDocument document) throws java.io.IOException {
        ElasticsearchClient client = indexService.requireClient();
        client.index(i -> i
                .index(indexService.indexName())
                .id(String.valueOf(document.getSkillId()))
                .document(document)
                .refresh(Refresh.WaitFor));
    }

    /** ES 的「索引不存在」在不同封装里以 type 或文本出现，两种都要认。 */
    static boolean isIndexMissing(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException typed
                    && typed.error() != null && "index_not_found_exception".equals(typed.error().type())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("index_not_found_exception")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    @Override
    public void delete(Long skillId) {
        if (skillId == null || !indexService.available()) {
            return;
        }
        try {
            ElasticsearchClient client = indexService.requireClient();
            client.delete(d -> d
                    .index(indexService.indexName())
                    .id(String.valueOf(skillId))
                    .refresh(Refresh.WaitFor));
        } catch (Exception e) {
            // 文档本来就不在（404）也走这里：删一个不存在的文档不算故障。
            log.debug("skill index delete skipped: id={} err={}", skillId, e.toString());
        }
    }
}
