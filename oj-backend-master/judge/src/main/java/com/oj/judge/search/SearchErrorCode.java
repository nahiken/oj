package com.oj.judge.search;

/**
 * 检索错误码。与 {@code HttpCodeEnum} 分开放：HttpCodeEnum 在 common-core 里被所有服务共享，
 * 检索码只属于 judge 的 search/ 领域，不往公共枚举里塞。
 */
public final class SearchErrorCode {

    /** ES 不可用 / 查询失败 */
    public static final int SEARCH_UNAVAILABLE = 30001;
    /** 开关关闭 */
    public static final int SEARCH_DISABLED = 30002;
    /** embedding 边车不可用 */
    public static final int EMBEDDING_UNAVAILABLE = 30005;
    /** 请求非法（空 query、mode 不认识、topK 越界） */
    public static final int SEARCH_BAD_REQUEST = 30004;

    private SearchErrorCode() {
    }
}
