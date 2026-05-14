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

    @Bean
    public RateLimiter slidingWindowRateLimiter(RedissonClient redisson) {
        return new SlidingWindowRateLimiter(redisson);
    }
}
