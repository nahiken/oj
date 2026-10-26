package com.oj.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 内部回调路径不对浏览器开放。
 *
 * <p>为什么需要这个过滤器：`/api/judge/**` 是公开路由，而 `ai` 写会话记录走的是
 * **直连 judge 8012**，从不经过网关。也就是说这条路径在网关上没有任何合法调用方——
 * 留着一个入口，任何登录用户都能伪造自己的辅导会话行，而这张表正是 `二次 AC 率` 的分母。
 *
 * <p>返回 404 而不是 403：不向扫描者确认这条路径存在。
 *
 * <p>顺序最高，必须在任何鉴权/转发之前拒绝。
 */
@Component
public class InternalPathFilter implements GlobalFilter, Ordered {

    /** 只能由服务内部调用的路径（前缀匹配）。 */
    private static final List<String> INTERNAL_PREFIXES = List.of("/api/judge/learning/ai/");

    private static final String BODY = "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Normalise before matching: a raw startsWith is bypassable with `/api/judge/learning/ai;x/session`
        // (Spring strips `;` path parameters downstream) or with duplicated slashes.
        String path = normalise(exchange.getRequest().getPath().pathWithinApplication().value());
        for (String prefix : INTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                return notFound(exchange);
            }
        }
        return chain.filter(exchange);
    }

    private static String normalise(String path) {
        return path.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
