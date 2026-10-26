package com.oj.judge.search.index;

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
 * 索引生命周期：建、删、重建、判存。
 *
 * <p>mapping 是资源文件 `/search/problem-index.json`，改它等于改契约：字段白名单（契约 0005 §2.2）
 * 与 `dense_vector dims=384` 都以那份文件为准。
 */
@Service
@Slf4j
public class SearchIndexService {

    private static final Resource MAPPING = new ClassPathResource("search/problem-index.json");

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final SearchProperties properties;

    public SearchIndexService(ObjectProvider<ElasticsearchClient> clientProvider, SearchProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    /** 开关打开且客户端已装配。关掉时没有任何 ES 连接。 */
    public boolean available() {
        return properties.isEnabled() && clientProvider.getIfAvailable() != null;
    }

    public ElasticsearchClient requireClient() {
        ElasticsearchClient client = properties.isEnabled() ? clientProvider.getIfAvailable() : null;
        if (client == null) {
            throw new SearchException(SearchErrorCode.SEARCH_DISABLED, "检索未开启（oj.search.enabled=false）");
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

    /** 幂等：存在就不动。评估脚本用 {@link #recreate()} 重置到已知状态。 */
    public void ensureIndex() {
        if (!exists()) {
            create();
        }
    }

    public void create() {
        try (Reader mapping = mapping()) {
            requireClient().indices().create(c -> c.index(indexName()).withJson(mapping));
            log.info("search index created: {}", indexName());
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
                log.info("search index deleted: {}", indexName());
            }
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public SearchException unavailable(Exception cause) {
        return new SearchException(SearchErrorCode.SEARCH_UNAVAILABLE,
                "检索不可用：" + cause.getClass().getSimpleName(), cause);
    }

    private Reader mapping() {
        try {
            return new InputStreamReader(MAPPING.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("缺少 mapping 资源：search/problem-index.json", e);
        }
    }
}
