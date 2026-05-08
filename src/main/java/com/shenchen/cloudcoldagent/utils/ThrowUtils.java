package com.shenchen.cloudcoldagent.utils;

import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;

/**
 * 异常抛出工具类
 *
 */
public class ThrowUtils {

    /**
     * 处理 `throw If` 对应逻辑。
     *
     * @param condition condition 参数。
     * @param runtimeException runtimeException 参数。
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * 处理 `throw If` 对应逻辑。
     *
     * @param condition condition 参数。
     * @param errorCode errorCode 参数。
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /**
     * 处理 `throw If` 对应逻辑。
     *
     * @param condition condition 参数。
     * @param errorCode errorCode 参数。
     * @param message message 参数。
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}
