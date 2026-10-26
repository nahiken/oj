package com.oj.common.core.utils;

import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;

/**
 * 抛异常工具类
 */
public class ThrowUtils {

    /**
     * 条件成立则抛异常
     *
     * @param condition
     * @param runtimeException
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * 条件成立则抛异常
     *
     * @param condition
     * @param httpCodeEnum
     */
    public static void throwIf(boolean condition, HttpCodeEnum httpCodeEnum) {
        throwIf(condition, new BusinessException(httpCodeEnum));
    }

    /**
     * 条件成立则抛异常
     * @param condition
     * @param httpCodeEnum
     * @param msg
     */
    public static void throwIf(boolean condition, HttpCodeEnum httpCodeEnum, String msg) {
        throwIf(condition, new BusinessException(httpCodeEnum, msg));
    }
}
