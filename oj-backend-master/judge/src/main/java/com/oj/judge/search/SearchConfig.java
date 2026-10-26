package com.oj.judge.search;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

/**
 * ES 8 客户端装配（ADR 0003：官方客户端，不用 Spring Data Elasticsearch）。
 *
 * <p>只在 {@code oj.search.enabled=true} 时创建：关掉开关时进程里没有任何 ES 连接，
 * 检索接口由 {@link com.oj.judge.search.query.SearchQueryService} 明确返回 503。
 *
 * <p>`close()` 在 transport 上，不在 client 上——client 自身没有销毁方法。
 */
@Configuration
@ConditionalOnProperty(prefix = "oj.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchConfig {

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport searchTransport(SearchProperties properties) {
        HttpHost[] hosts = new HttpHost[properties.getHosts().length];
        for (int i = 0; i < properties.getHosts().length; i++) {
            hosts[i] = HttpHost.create(properties.getHosts()[i]);
        }
        RestClient restClient = RestClient.builder(hosts)
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(properties.getConnectTimeoutMs())
                        .setSocketTimeout(properties.getSocketTimeoutMs()))
                .build();
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient searchClient(ElasticsearchTransport searchTransport) {
        return new ElasticsearchClient(searchTransport);
    }
}
