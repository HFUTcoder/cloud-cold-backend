package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.aop.DistributeLockAspect;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `DistributeLockConfiguration` 类型实现。
 */
@Configuration
public class DistributeLockConfiguration {

    /**
     * 处理 `distribute Lock Aspect` 对应逻辑。
     *
     * @param redisson redisson 参数。
     * @return 返回处理结果。
     */
    @Bean
    @ConditionalOnMissingBean
    public DistributeLockAspect distributeLockAspect(RedissonClient redisson){
        return new DistributeLockAspect(redisson);
    }
}
