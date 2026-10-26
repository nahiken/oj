package com.oj.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * 只作用于 /api/ai/** 的鉴权过滤器。
 *
 * <p>边界：网关只证明「token 是我们签的」，token 是否还活着由 judge 的 @TokenCheck 证明。
 * 因此这里不查 Redis，也不引 common-auth（会把 servlet 依赖带进 WebFlux 栈）。
 *
 * <p>顺序很重要：先删除外部传入的 X-Uid，再注入验签得到的 uid，否则就是越权。
 *
 * <p>HS256 用 JDK 自带的 Mac 实现：hutool 的 JWT 会加载 BouncyCastle，而本机 JCE 无法
 * 认证 BC 的签名，验签会直接抛 CryptoException。
 */
@Component
public class AiAuthFilter implements GlobalFilter, Ordered {

    private static final String PREFIX = "/api/ai/";
    private static final String COOKIE_NAME = "token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${oj.user.secret-key:}")
    private String secretKey;

    @PostConstruct
    public void checkSecret() {
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("oj.user.secret-key must not be empty: the gateway cannot verify AI requests without it");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(PREFIX)) {
            return chain.filter(exchange);
        }
        // 预检请求不携带 Cookie，交给 CORS 过滤器回答。
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        String token = tokenOf(exchange);
        if (!StringUtils.hasText(token)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "missing token");
        }

        JsonNode payload;
        try {
            payload = verify(token);
        } catch (Exception e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "invalid token");
        }
        String uid = payload.path("uid").asText("");
        if (!StringUtils.hasText(uid)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "token has no uid");
        }
        String role = payload.path("userRole").asText("");

        Consumer<HttpHeaders> headers = h -> {
            h.remove("X-Uid");
            h.set("X-Uid", uid);
            if (StringUtils.hasText(role)) {
                h.remove("X-User-Role");
                h.set("X-User-Role", role);
            }
        };
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** Verifies an HS256 JWT and returns its payload; throws on any mismatch. */
    private JsonNode verify(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed token");
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonNode header = MAPPER.readTree(decoder.decode(parts[0]));
        // Refuse anything but HS256: accepting the header's own algorithm is the
        // classic "alg: none" hole.
        if (!"HS256".equals(header.path("alg").asText())) {
            throw new IllegalArgumentException("unsupported alg");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        byte[] actual = decoder.decode(parts[2]);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("bad signature");
        }
        JsonNode payload = MAPPER.readTree(decoder.decode(parts[1]));
        long exp = payload.path("exp").asLong(0L);
        if (exp > 0 && exp * 1000L <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("expired");
        }
        return payload;
    }

    private static String tokenOf(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
