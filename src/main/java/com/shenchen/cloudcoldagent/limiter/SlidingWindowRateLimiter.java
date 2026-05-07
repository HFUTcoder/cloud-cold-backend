package com.shenchen.cloudcoldagent.limiter;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

/**
 * 滑动窗口限流服务
 *
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private RedissonClient redissonClient;

    private static final String LIMIT_KEY_PREFIX = "cloud_cold:limit:";

    /**
     * 创建 `SlidingWindowRateLimiter` 实例。
     *
     * @param redissonClient redissonClient 参数。
     */
    public SlidingWindowRateLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 处理 `try Acquire` 对应逻辑。
     *
     * @param key key 参数。
     * @param limit limit 参数。
     * @param windowSize windowSize 参数。
     * @return 返回处理结果。
     */
    @Override
    public Boolean tryAcquire(String key, int limit, int windowSize) {
        RRateLimiter rRateLimiter = redissonClient.getRateLimiter(LIMIT_KEY_PREFIX + key);

        if (!rRateLimiter.isExists()) {
            rRateLimiter.trySetRate(RateType.OVERALL, limit, windowSize, RateIntervalUnit.SECONDS);
        }

        return rRateLimiter.tryAcquire();
    }
}
