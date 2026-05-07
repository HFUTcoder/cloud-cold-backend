package com.shenchen.cloudcoldagent.exception;

/**
 * 分布式锁异常
 *
 */
public class DistributeLockException extends RuntimeException {

    /**
     * 创建 `DistributeLockException` 实例。
     */
    public DistributeLockException() {
    }

    /**
     * 创建 `DistributeLockException` 实例。
     *
     * @param message message 参数。
     */
    public DistributeLockException(String message) {
        super(message);
    }

    /**
     * 创建 `DistributeLockException` 实例。
     *
     * @param message message 参数。
     * @param cause cause 参数。
     */
    public DistributeLockException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建 `DistributeLockException` 实例。
     *
     * @param cause cause 参数。
     */
    public DistributeLockException(Throwable cause) {
        super(cause);
    }

    /**
     * 创建 `DistributeLockException` 实例。
     *
     * @param message message 参数。
     * @param cause cause 参数。
     * @param enableSuppression enableSuppression 参数。
     * @param writableStackTrace writableStackTrace 参数。
     */
    public DistributeLockException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
