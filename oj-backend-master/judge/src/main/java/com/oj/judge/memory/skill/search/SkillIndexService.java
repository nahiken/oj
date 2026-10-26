package com.oj.judge.memory.skill.search;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.oj.judge.search.SearchErrorCode;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.SearchProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;

/**
 * `learning_skill_v1` 的生命周期：建、删、重建、判存。
 *
 * <p>ES 客户端复用 `oj.search` 装配的那个 Bean（同一台 ES、同一套超时）；
 * 这里只负责索引名与 mapping，所以关掉 `oj.search.enabled` 时本链路也是 503，
 * 不会出现「题目检索关了、Skill 检索还连着一个不该存在的连接」。
 */
@Service
@Slf4j
public class SkillIndexService {

    private static final Resource MAPPING = new ClassPathResource("search/skill-index.json");

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final SkillSearchProperties properties;
    private final SearchProperties searchProperties;

    public SkillIndexService(ObjectProvider<ElasticsearchClient> clientProvider, SkillSearchProperties properties,
            SearchProperties searchProperties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.searchProperties = searchProperties;
    }

    /** 开关打开且 ES 客户端已装配。 */
    public boolean available() {
        return properties.isEnabled() && clientProvider.getIfAvailable() != null;
    }

    public ElasticsearchClient requireClient() {
        ElasticsearchClient client = available() ? clientProvider.getIfAvailable() : null;
        if (client == null) {
            throw new SearchException(SearchErrorCode.SEARCH_DISABLED, "Skill 检索未开启（oj.skill-search.enabled=false）");
        }
        return client;
    }

    public String indexName() {
        return properties.getIndexName();
    }

    public boolean exists() {
        try {
            return requireClient().indices().exists(e -> e.index(indexName())).value();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /** 幂等：存在就不动。 */
    public void ensureIndex() {
        if (!exists()) {
            create();
        }
    }

    public void create() {
        try (Reader mapping = mapping()) {
            requireClient().indices().create(c -> c.index(indexName()).withJson(mapping));
            log.info("skill index created: {}", indexName());
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public void recreate() {
        deleteIfExists();
        create();
    }

    public void deleteIfExists() {
        try {
            ElasticsearchClient client = requireClient();
            if (client.indices().exists(e -> e.index(indexName())).value()) {
                client.indices().delete(d -> d.index(indexName()));
                log.info("skill index deleted: {}", indexName());
            }
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public SearchException unavailable(Exception cause) {
        return new SearchException(SearchErrorCode.SEARCH_UNAVAILABLE,
                "Skill 检索不可用：" + cause.getClass().getSimpleName(), cause);
    }

    private Reader mapping() {
        try {
            return new InputStreamReader(MAPPING.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("缺少 mapping 资源：search/skill-index.json", e);
        }
    }
}
