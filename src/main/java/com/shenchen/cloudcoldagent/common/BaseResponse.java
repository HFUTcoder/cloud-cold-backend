package com.shenchen.cloudcoldagent.common;

import com.shenchen.cloudcoldagent.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * `BaseResponse` 类型实现。
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;
    private T data;
    private String message;

    /**
     * 创建 `BaseResponse` 实例。
     *
     * @param code code 参数。
     * @param data data 参数。
     * @param message message 参数。
     */
    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    /**
     * 创建 `BaseResponse` 实例。
     *
     * @param code code 参数。
     * @param data data 参数。
     */
    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    /**
     * 创建 `BaseResponse` 实例。
     *
     * @param errorCode errorCode 参数。
     */
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
