package com.oj.judge.search;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oj.common.core.dto.R;

/**
 * 检索异常 → **真实 HTTP 状态码**。
 *
 * <p>本仓库其它接口的惯例是 HTTP 200 + body 里的业务码（{@code GlobalExceptionHandler}），
 * 但检索的 503 是给机器看的上游契约（P6 的 AI 编排要据此决定"没引用来源"而不是"没有相关内容"），
 * 因此这里显式返回 503/400。Order 最高，避免被全局处理器接走。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SearchExceptionHandler {

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<R<Void>> handle(SearchException e) {
        HttpStatus status = e.getCode() == SearchErrorCode.SEARCH_BAD_REQUEST
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(R.error(e.getCode(), e.getMessage()));
    }
}
