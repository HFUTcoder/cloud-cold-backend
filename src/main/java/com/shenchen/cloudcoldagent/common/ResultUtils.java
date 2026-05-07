package com.shenchen.cloudcoldagent.common;


import com.shenchen.cloudcoldagent.exception.ErrorCode;

/**
 * 快速构造响应结果的工具类
 */
public class ResultUtils {

    /**
     * 处理 `success` 对应逻辑。
     *
     * @param data data 参数。
     * @return 返回处理结果。
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 处理 `error` 对应逻辑。
     *
     * @param errorCode errorCode 参数。
     * @return 返回处理结果。
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 处理 `error` 对应逻辑。
     *
     * @param code code 参数。
     * @param message message 参数。
     * @return 返回处理结果。
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 处理 `error` 对应逻辑。
     *
     * @param errorCode errorCode 参数。
     * @param message message 参数。
     * @return 返回处理结果。
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}