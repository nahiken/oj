package com.oj.judge.search;

/** 检索侧的失败是**显式**的：503/501/400，绝不静默返回空结果（契约 0005 §4.5）。 */
public class SearchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public SearchException(int code, String message) {
        super(message);
        this.code = code;
    }

    public SearchException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
