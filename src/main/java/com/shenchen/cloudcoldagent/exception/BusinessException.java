package com.shenchen.cloudcoldagent.exception;

import lombok.Getter;

/**
 * 自定义业务异常
 *
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final int code;

    /**
     * 创建 `BusinessException` 实例。
     *
     * @param code code 参数。
     * @param message message 参数。
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 创建 `BusinessException` 实例。
     *
     * @param errorCode errorCode 参数。
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 创建 `BusinessException` 实例。
     *
     * @param errorCode errorCode 参数。
     * @param message message 参数。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
