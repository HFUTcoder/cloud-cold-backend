package com.shenchen.cloudcoldagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆处理线程池配置。
 */
@Configuration
public class LongTermMemoryConfig {

    @Bean("longTermMemoryExecutor")
    public Executor longTermMemoryExecutor() {
        return new ThreadPoolExecutor(
                1, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
