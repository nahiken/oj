package com.oj.judge.search.embed;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.oj.judge.search.SearchErrorCode;
import com.oj.judge.search.SearchException;
import com.oj.judge.search.SearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * embedding 边车的 HTTP 实现（契约 0005 §4.3、ADR 0002）。
 *
 * <p>**前缀只有一个强制点：边车。** 这里只传原文 + `kind`，不拼 `query:` / `passage:`；
 * 边车负责加前缀并拒绝重复前缀，所以既不会漏加也不会加两遍。
 *
 * <p>失败一律抛 {@link SearchException}（503 / 30005）：dense 不可用要显式告诉调用方，
 * 不能悄悄退化成 BM25。
 *
 * <p>**为什么用 HttpURLConnection 而不是 java.net.http.HttpClient**：同一份 JSON，
 * `HttpClient` 发出去被 uvicorn 读成空 body（FastAPI 回 422 `body missing`），
 * `HttpURLConnection` 立刻 200。这是实测踩到的互操作问题，不是配置问题。
 */
@Component
@Slf4j
public class HttpEmbeddingClient implements EmbeddingClient {

    private final SearchProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 边车实际使用的模型 id，写进索引的 `modelId` 字段（换模型时旧向量可辨） */
    private volatile String modelId = "unknown";

    public HttpEmbeddingClient(SearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public float[] embedQuery(String text) {
        List<float[]> vectors = embed(List.of(text), "query", properties.getEmbeddingTimeoutMs());
        return vectors.get(0);
    }

    /**
     * 文档侧批量：一批 32 条、每条 512 token，CPU 上约 12s，所以用更长的超时
     * （{@code embedding-index-timeout-ms}），而在线查询仍卡 2s。
     */
    @Override
    public List<float[]> embedPassages(List<String> texts) {
        return embed(texts, "passage", properties.getEmbeddingIndexTimeoutMs());
    }

    private List<float[]> embed(List<String> texts, String kind, int timeoutMs) {
        if (!properties.isEmbeddingEnabled()) {
            throw unavailable("embedding 已关闭（oj.search.embedding-enabled=false）");
        }
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }
        byte[] payload = serialize(texts, kind);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(properties.getEmbeddingUrl() + "/embed").toURL()
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(properties.getEmbeddingTimeoutMs());
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                String detail = readAll(connection.getErrorStream());
                if (detail.length() > 200) {
                    detail = detail.substring(0, 200);
                }
                throw unavailable("embedding 边车返回 " + status + " " + detail);
            }
            return parse(readAll(connection.getInputStream()), texts.size());
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable("embedding 边车不可用：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] serialize(List<String> texts, String kind) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode array = body.putArray("texts");
        texts.forEach(array::add);
        body.put("kind", kind);
        try {
            return mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw unavailable("序列化 embedding 请求失败：" + e.getMessage());
        }
    }

    private List<float[]> parse(String json, int expected) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode model = root.get("modelId");
            if (model != null) {
                modelId = model.asText();
            }
            JsonNode vectors = root.get("vectors");
            if (vectors == null || !vectors.isArray() || vectors.size() != expected) {
                throw unavailable("embedding 响应条数不匹配");
            }
            List<float[]> out = new ArrayList<>(expected);
            for (JsonNode vector : vectors) {
                float[] values = new float[vector.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                out.add(values);
            }
            return out;
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable("embedding 响应解析失败：" + e.getMessage());
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private SearchException unavailable(String message) {
        log.warn("embedding unavailable: {}", message);
        return new SearchException(SearchErrorCode.EMBEDDING_UNAVAILABLE, message);
    }
}
