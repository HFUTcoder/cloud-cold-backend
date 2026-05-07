package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.limiter.RateLimiter;
import com.shenchen.cloudcoldagent.limiter.SlidingWindowRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `RateLimiterConfiguration` 类型实现。
 */
@Configuration
public class RateLimiterConfiguration {

    /**
     * 处理 `sliding Window Rate Limiter` 对应逻辑。
     *
     * @param redisson redisson 参数。
     * @return 返回处理结果。
     */
    @Bean
    public RateLimiter slidingWindowRateLimiter(RedissonClient redisson) {
        return new SlidingWindowRateLimiter(redisson);
    }
}
