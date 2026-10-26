package com.oj.judge.memory.skill.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.search.SearchErrorCode;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.embed.EmbeddingClient;
import com.oj.judge.search.fusion.Rrf;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 检索：BM25 + Dense 两路召回，RRF 融合（spec D20，复用契约 0005 §4.4 的管道）。
 *
 * <p>可见性是**查询条件**而不是应用层过滤：`scope=PLATFORM` 或 `(scope=PERSONAL 且 uid=我)`。
 * 用应用层删结果的写法会让「别人的 Skill 进过返回列表」这件事在日志和延迟里都看不出来。
 */
@Service
@Slf4j
public class SkillSearchService {

    private static final List<String> QUERY_FIELDS = List.of("name^3", "content", "tags");

    private final SkillIndexService indexService;
    private final SkillSearchProperties properties;
    private final EmbeddingClient embeddingClient;

    public SkillSearchService(SkillIndexService indexService, SkillSearchProperties properties,
            EmbeddingClient embeddingClient) {
        this.indexService = indexService;
        this.properties = properties;
        this.embeddingClient = embeddingClient;
    }

    public SkillSearchResp search(Long uid, String rawQuery, Integer topK) {
        long started = System.nanoTime();
        String text = normalizeQuery(rawQuery);
        int size = normalizeTopK(topK);
        if (!indexService.available()) {
            throw new SearchException(SearchErrorCode.SEARCH_DISABLED, "Skill 检索未开启（oj.skill-search.enabled=false）");
        }
        ElasticsearchClient client = indexService.requireClient();
        int candidates = Math.max(size, properties.getHybridCandidates());

        Route keyword = bm25(client, text, uid, candidates);
        Route vector = dense(client, text, uid, candidates);

        List<Long> bm25Ids = idsOf(keyword.response());
        List<Long> denseIds = idsOf(vector.response());
        Map<Long, Double> fused = Rrf.fuse(List.of(bm25Ids, denseIds),
                List.of(properties.getRrfWeight(), properties.getRrfWeight()), properties.getRrfK());
        List<Long> ordered = Rrf.order(fused, List.of(bm25Ids, denseIds));

        Map<Long, Hit<SkillDocument>> documents = new LinkedHashMap<>();
        keyword.response().hits().hits().forEach(hit -> documents.putIfAbsent(idOf(hit), hit));
        vector.response().hits().hits().forEach(hit -> documents.putIfAbsent(idOf(hit), hit));
        Map<Long, Integer> bm25Rank = ranksOf(bm25Ids);
        Map<Long, Integer> denseRank = ranksOf(denseIds);

        List<SkillSearchHit> hits = new ArrayList<>();
        for (Long id : ordered) {
            if (hits.size() >= size) {
                break;
            }
            Hit<SkillDocument> hit = documents.get(id);
            if (hit == null || hit.source() == null) {
                continue;
            }
            SkillSearchHit out = new SkillSearchHit();
            SkillDocument document = hit.source();
            out.setSkillId(document.getSkillId() != null ? document.getSkillId() : id);
            out.setName(document.getName());
            out.setContent(document.getContent());
            out.setTags(document.getTags() == null ? new ArrayList<>() : document.getTags());
            out.setScope(document.getScope());
            out.setBm25Rank(bm25Rank.get(id));
            out.setDenseRank(denseRank.get(id));
            out.setScore(fused.get(id));
            hits.add(out);
        }

        SkillSearchResp response = new SkillSearchResp();
        response.setQuery(text);
        response.setTopK(size);
        response.setTotal(ordered.size());
        response.setHits(hits);
        response.setEsMs(keyword.esMs() + vector.esMs());
        response.setEmbedMs(vector.embedMs());
        response.setTookMs((System.nanoTime() - started) / 1_000_000L);
        return response;
    }

    private Route bm25(ElasticsearchClient client, String text, Long uid, int size) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.must(m -> m.multiMatch(mm -> mm.query(text).fields(QUERY_FIELDS)));
        bool.filter(visibility(uid));
        Query query = Query.of(q -> q.bool(bool.build()));
        long esStart = System.nanoTime();
        try {
            SearchResponse<SkillDocument> response = client.search(s -> s
                    .index(indexService.indexName())
                    .query(query)
                    .size(size)
                    .trackTotalHits(t -> t.enabled(true)), SkillDocument.class);
            return new Route(response, 0L, (System.nanoTime() - esStart) / 1_000_000L);
        } catch (Exception e) {
            log.warn("skill bm25 query failed: {}", e.toString());
            throw indexService.unavailable(e);
        }
    }

    private Route dense(ElasticsearchClient client, String text, Long uid, int size) {
        long embedStart = System.nanoTime();
        float[] vector = embeddingClient.embedQuery(text);
        long embedMs = (System.nanoTime() - embedStart) / 1_000_000L;
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float value : vector) {
            queryVector.add(value);
        }
        Query filter = visibility(uid);
        long esStart = System.nanoTime();
        try {
            SearchResponse<SkillDocument> response = client.search(s -> s
                    .index(indexService.indexName())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVector)
                            .k(size)
                            .numCandidates(Math.max(size * 4, 100))
                            .filter(filter))
                    .size(size), SkillDocument.class);
            return new Route(response, embedMs, (System.nanoTime() - esStart) / 1_000_000L);
        } catch (Exception e) {
            log.warn("skill dense query failed: {}", e.toString());
            throw indexService.unavailable(e);
        }
    }

    /**
     * 可见性过滤：`scope=PLATFORM` 或 `scope=PERSONAL 且 uid=当前用户`。
     *
     * <p>用 `terms` / `term` 在 ES 里过滤，不是取回来再删（spec D20 的原文要求）。
     * uid 缺失（没有登录态）时只返回平台 Skill——不返回"所有人的个人 Skill"。
     */
    static Query visibility(Long uid) {
        BoolQuery.Builder visible = new BoolQuery.Builder();
        visible.should(s -> s.term(t -> t.field("scope").value(LearningSkillDO.SCOPE_PLATFORM)));
        if (uid != null) {
            visible.should(s -> s.bool(b -> b
                    .filter(f -> f.term(t -> t.field("scope").value(LearningSkillDO.SCOPE_PERSONAL)))
                    .filter(f -> f.term(t -> t.field("uid").value(String.valueOf(uid))))));
        }
        visible.minimumShouldMatch("1");
        return Query.of(q -> q.bool(visible.build()));
    }

    private List<Long> idsOf(SearchResponse<SkillDocument> response) {
        List<Long> ids = new ArrayList<>(response.hits().hits().size());
        for (Hit<SkillDocument> hit : response.hits().hits()) {
            Long id = idOf(hit);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Long idOf(Hit<SkillDocument> hit) {
        if (hit.source() != null && hit.source().getSkillId() != null) {
            return hit.source().getSkillId();
        }
        try {
            return Long.valueOf(hit.id());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<Long, Integer> ranksOf(List<Long> ids) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        int rank = 0;
        for (Long id : ids) {
            rank++;
            ranks.putIfAbsent(id, rank);
        }
        return ranks;
    }

    private String normalizeQuery(String rawQuery) {
        String text = rawQuery == null ? "" : rawQuery.trim();
        if (text.isEmpty()) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "q 不能为空");
        }
        if (text.length() > 200) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "q 过长（上限 200 字符）");
        }
        return text;
    }

    private int normalizeTopK(Integer topK) {
        int value = topK == null ? properties.getDefaultTopK() : topK;
        if (value < 1 || value > properties.getMaxTopK()) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST,
                    "topK 必须在 1.." + properties.getMaxTopK() + " 之间");
        }
        return value;
    }

    /** 单路召回：响应 + 拆解耗时（embedding 只有 dense 那一路有）。 */
    private record Route(SearchResponse<SkillDocument> response, long embedMs, long esMs) {
    }
}
