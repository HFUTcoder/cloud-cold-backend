package com.shenchen.cloudcoldagent.limiter;

/**
 * 限流服务
 *
 */
public interface RateLimiter {

    /**
     * 处理 `try Acquire` 对应逻辑。
     *
     * @param key key 参数。
     * @param limit limit 参数。
     * @param windowSize windowSize 参数。
     * @return 返回处理结果。
     */
    public Boolean tryAcquire(String key, int limit, int windowSize);
}
