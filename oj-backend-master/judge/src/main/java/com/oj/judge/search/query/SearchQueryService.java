package com.oj.judge.search.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oj.judge.search.SearchErrorCode;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.SearchProperties;
import com.oj.judge.search.embed.EmbeddingClient;
import com.oj.judge.search.fusion.Rrf;
import com.oj.judge.search.index.ProblemDocument;
import com.oj.judge.search.index.SearchIndexService;
import com.oj.judge.search.metrics.SearchMetrics;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索编排：`bm25` / `dense` / `hybrid`（RRF，契约 0005 §4.4）。
 *
 * <p>`hybrid` 两路各取 {@code hybrid-candidates}（默认 50）后再融合截前 `topK`；
 * `k=60`、`w=1.0` 是契约值，**不在评估集上调参**。
 *
 * <p>失败一律抛 {@link SearchException}：ES 不可用 30001，边车不可用 30005，都是 503。
 */
@Service
@Slf4j
public class SearchQueryService {

    private static final String MODE_BM25 = "bm25";
    private static final String MODE_DENSE = "dense";
    private static final String MODE_HYBRID = "hybrid";

    private final SearchIndexService indexService;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final EmbeddingClient embeddingClient;

    public SearchQueryService(SearchIndexService indexService, SearchProperties properties, SearchMetrics metrics,
            EmbeddingClient embeddingClient) {
        this.indexService = indexService;
        this.properties = properties;
        this.metrics = metrics;
        this.embeddingClient = embeddingClient;
    }

    public SearchQueryResp query(SearchQueryReq request) {
        long started = System.nanoTime();
        String text = normalizeQuery(request);
        String mode = normalizeMode(request.getMode());
        int topK = normalizeTopK(request.getTopK());
        String lang = normalizeLang(request.getLang());

        if (!indexService.available()) {
            metrics.degraded();
            throw new SearchException(SearchErrorCode.SEARCH_DISABLED, "检索未开启（oj.search.enabled=false）");
        }

        ElasticsearchClient client = indexService.requireClient();
        SearchQueryResp out;
        if (MODE_BM25.equals(mode)) {
            out = bm25Only(client, text, lang, topK);
        } else if (MODE_DENSE.equals(mode)) {
            out = denseOnly(client, text, lang, topK);
        } else {
            out = hybrid(client, text, lang, topK);
        }

        out.setQuery(text);
        out.setMode(mode);
        out.setTopK(topK);
        out.setTookMs((System.nanoTime() - started) / 1_000_000L);
        if (MODE_BM25.equals(mode)) {
            metrics.bm25(out.getEsMs(), out.getTookMs());
        } else if (MODE_DENSE.equals(mode)) {
            metrics.dense(out.getEsMs(), out.getTookMs());
        } else {
            metrics.hybrid(out.getEsMs(), out.getTookMs());
        }
        return out;
    }

    private SearchQueryResp bm25Only(ElasticsearchClient client, String text, String lang, int topK) {
        Route route = schedule(client, text, lang, topK, false);
        SearchQueryResp out = base(route);
        out.setHits(hitsFrom(route, false));
        return out;
    }

    private SearchQueryResp denseOnly(ElasticsearchClient client, String text, String lang, int topK) {
        Route route = schedule(client, text, lang, topK, true);
        SearchQueryResp out = base(route);
        out.setHits(hitsFrom(route, true));
        return out;
    }

    /**
     * RRF：两路各取候选，按 `w/(k+rank)` 求和后截前 topK。
     * 响应里两路名次都保留，单路未命中记 null——这是"为什么融合后更好"的唯一可复核证据。
     */
    private SearchQueryResp hybrid(ElasticsearchClient client, String text, String lang, int topK) {
        int candidates = Math.max(topK, properties.getHybridCandidates());
        Route keyword = schedule(client, text, lang, candidates, false);
        Route vector = schedule(client, text, lang, candidates, true);

        List<Long> bm25Ids = idsOf(keyword);
        List<Long> denseIds = idsOf(vector);
        Map<Long, Double> fused = Rrf.fuse(List.of(bm25Ids, denseIds),
                List.of(properties.getRrfWeight(), properties.getRrfWeight()), properties.getRrfK());
        List<Long> ordered = Rrf.order(fused, List.of(bm25Ids, denseIds));

        Map<Long, Hit<ProblemDocument>> documents = new LinkedHashMap<>();
        keyword.response().hits().hits().forEach(hit -> documents.putIfAbsent(idOf(hit), hit));
        vector.response().hits().hits().forEach(hit -> documents.putIfAbsent(idOf(hit), hit));
        Map<Long, Integer> bm25Rank = ranksOf(bm25Ids);
        Map<Long, Integer> denseRank = ranksOf(denseIds);

        List<SearchHitVo> hits = new ArrayList<>();
        for (Long id : ordered) {
            if (hits.size() >= topK) {
                break;
            }
            Hit<ProblemDocument> hit = documents.get(id);
            if (hit == null) {
                continue;
            }
            SearchHitVo vo = toVo(hit);
            vo.setBm25Rank(bm25Rank.get(id));
            vo.setDenseRank(denseRank.get(id));
            Double score = fused.get(id);
            vo.setRrfScore(score);
            vo.setScore(score);
            hits.add(vo);
        }

        SearchQueryResp out = base(keyword);
        out.setHits(hits);
        out.setTotal(ordered.size());
        out.setEsMs(keyword.esMs() + vector.esMs());
        out.setEmbedMs(vector.embedMs());
        return out;
    }

    /** 一次单路召回：记录 ES 耗时与（dense 才有）边车耗时，供 P50/P95 归因。 */
    private Route schedule(ElasticsearchClient client, String text, String lang, int size, boolean dense) {
        if (!dense) {
            long esStart = System.nanoTime();
            SearchResponse<ProblemDocument> response = searchBm25(client, text, lang, size);
            long esMs = (System.nanoTime() - esStart) / 1_000_000L;
            return new Route(response, 0L, esMs);
        }
        long embedStart = System.nanoTime();
        float[] vector = embeddingClient.embedQuery(text);
        long embedMs = (System.nanoTime() - embedStart) / 1_000_000L;
        long esStart = System.nanoTime();
        SearchResponse<ProblemDocument> response = searchKnn(client, vector, lang, size);
        long esMs = (System.nanoTime() - esStart) / 1_000_000L;
        return new Route(response, embedMs, esMs);
    }

    private SearchResponse<ProblemDocument> searchBm25(ElasticsearchClient client, String text, String lang, int size) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        List<String> fields = queryFields(lang);
        bool.must(m -> m.multiMatch(mm -> mm.query(text).fields(fields)));
        bool.filter(visibilityFilter(lang));
        Query query = Query.of(q -> q.bool(bool.build()));

        try {
            return client.search(s -> s
                    .index(indexService.indexName())
                    .query(query)
                    .size(size)
                    .trackTotalHits(t -> t.enabled(true))
                    .highlight(h -> h
                            .fields("title", f -> f)
                            .fields("content.en", f -> f)
                            .fields("content.zh", f -> f)
                            .fragmentSize(properties.getHighlightFragmentSize())
                            .numberOfFragments(1)
                            .preTags("<em>")
                            .postTags("</em>")), ProblemDocument.class);
        } catch (Exception e) {
            metrics.degraded();
            log.warn("bm25 query failed: {}", e.toString());
            throw indexService.unavailable(e);
        }
    }

    private SearchResponse<ProblemDocument> searchKnn(ElasticsearchClient client, float[] vector, String lang, int size) {
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float value : vector) {
            queryVector.add(value);
        }
        Query filter = visibilityFilter(lang);
        try {
            return client.search(s -> s
                    .index(indexService.indexName())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVector)
                            .k(size)
                            .numCandidates(Math.max(size * 4, 100))
                            .filter(filter))
                    .size(size), ProblemDocument.class);
        } catch (Exception e) {
            metrics.degraded();
            log.warn("dense query failed: {}", e.toString());
            throw indexService.unavailable(e);
        }
    }

    private SearchQueryResp base(Route route) {
        SearchQueryResp out = new SearchQueryResp();
        out.setTotal(route.response().hits().total() == null ? route.response().hits().hits().size()
                : route.response().hits().total().value());
        out.setEsMs(route.esMs());
        out.setEmbedMs(route.embedMs());
        return out;
    }

    private List<SearchHitVo> hitsFrom(Route route, boolean dense) {
        List<SearchHitVo> hits = new ArrayList<>();
        int rank = 0;
        for (Hit<ProblemDocument> hit : route.response().hits().hits()) {
            rank++;
            SearchHitVo vo = toVo(hit);
            if (dense) {
                vo.setDenseRank(rank);
            } else {
                vo.setBm25Rank(rank);
            }
            hits.add(vo);
        }
        return hits;
    }

    private SearchHitVo toVo(Hit<ProblemDocument> hit) {
        SearchHitVo vo = new SearchHitVo();
        ProblemDocument doc = hit.source();
        vo.setProblemId(doc != null && doc.getProblemId() != null ? doc.getProblemId() : parseId(hit.id()));
        vo.setTitle(doc != null ? doc.getTitle() : null);
        vo.setTags(doc != null ? doc.getTags() : new ArrayList<>());
        vo.setDifficulty(doc != null ? doc.getDifficulty() : null);
        vo.setSnippet(snippetOf(hit, doc));
        vo.setScore(hit.score());
        return vo;
    }

    private List<Long> idsOf(Route route) {
        List<Long> ids = new ArrayList<>(route.response().hits().hits().size());
        for (Hit<ProblemDocument> hit : route.response().hits().hits()) {
            Long id = idOf(hit);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
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

    private Long idOf(Hit<ProblemDocument> hit) {
        if (hit.source() != null && hit.source().getProblemId() != null) {
            return hit.source().getProblemId();
        }
        return parseId(hit.id());
    }

    /**
     * 按语种选字段：中文走 smartcn 的 `title.smartcn` / `content.zh`，英文走 standard。
     * 不指定语种时两种都查——一份文档只会在其中一种里有正文，不会互相干扰。
     */
    static List<String> queryFields(String lang) {
        List<String> fields = new ArrayList<>();
        if (lang == null) {
            fields.add("title^2");
            fields.add("title.smartcn^2");
            fields.add("content.en");
            fields.add("content.zh");
            fields.add("tags");
        } else if ("zh".equals(lang)) {
            fields.add("title.smartcn^2");
            fields.add("content.zh");
            fields.add("tags");
        } else {
            fields.add("title^2");
            fields.add("content.en");
            fields.add("tags");
        }
        return fields;
    }

    /** 两条路径共用的可见性过滤：软删除永不返回，`lang` 可选。 */
    private static Query visibilityFilter(String lang) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(f -> f.term(t -> t.field("isDelete").value(false)));
        if (lang != null) {
            bool.filter(f -> f.term(t -> t.field("lang").value(lang)));
        }
        return Query.of(q -> q.bool(bool.build()));
    }

    /**
     * BM25 用 ES highlight；dense 没有查询词可高亮，退回正文前缀（索引里存的就是纯文本）。
     */
    String snippetOf(Hit<?> hit, ProblemDocument doc) {
        Map<String, List<String>> highlight = hit.highlight();
        if (highlight != null && !highlight.isEmpty()) {
            Map<String, List<String>> ordered = new LinkedHashMap<>(highlight);
            for (String key : new String[] { "content.zh", "content.en", "title.smartcn", "title" }) {
                List<String> fragments = ordered.get(key);
                if (fragments != null && !fragments.isEmpty()) {
                    return fragments.get(0);
                }
            }
        }
        if (doc == null || doc.getContent() == null) {
            return "";
        }
        String body = doc.getContent().getZh() != null ? doc.getContent().getZh() : doc.getContent().getEn();
        if (body == null) {
            return "";
        }
        int limit = Math.min(properties.getHighlightFragmentSize(), body.length());
        return body.substring(0, limit);
    }

    private Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeQuery(SearchQueryReq request) {
        if (request == null) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "请求体不能为空");
        }
        String text = request.getQuery() == null ? "" : request.getQuery().trim();
        if (text.isEmpty()) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "query 不能为空");
        }
        if (text.length() > 500) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "query 过长（上限 500 字符）");
        }
        return text;
    }

    private String normalizeMode(String mode) {
        String value = (mode == null || mode.isBlank()) ? MODE_HYBRID : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_BM25.equals(value) && !MODE_DENSE.equals(value) && !MODE_HYBRID.equals(value)) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "mode 只能是 bm25 / dense / hybrid");
        }
        return value;
    }

    private int normalizeTopK(Integer topK) {
        int value = topK == null ? properties.getDefaultTopK() : topK;
        if (value < 1 || value > properties.getMaxTopK()) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST,
                    "topK 必须在 1.." + properties.getMaxTopK() + " 之间");
        }
        return value;
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        String value = lang.trim().toLowerCase(Locale.ROOT);
        if (!"en".equals(value) && !"zh".equals(value)) {
            throw new SearchException(SearchErrorCode.SEARCH_BAD_REQUEST, "lang 只能是 en / zh");
        }
        return value;
    }

    /** 单路召回的返回值：响应 + 拆解耗时。 */
    private record Route(SearchResponse<ProblemDocument> response, long embedMs, long esMs) {
    }
}
