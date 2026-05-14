package com.shenchen.cloudcoldagent.limiter;

/**
 * 限流服务
 *
 */
public interface RateLimiter {

    /**
     * 尝试获取令牌，基于 Redis 滑动窗口限流。
     */
    Boolean tryAcquire(String key, int limit, int windowSize);
}
