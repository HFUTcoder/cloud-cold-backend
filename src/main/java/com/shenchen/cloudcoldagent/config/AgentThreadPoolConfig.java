package com.shenchen.cloudcoldagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Agent 业务线程池统一配置。
 */
@Configuration
@EnableConfigurationProperties
public class AgentThreadPoolConfig {

    @Bean("agentToolTaskExecutor")
    @ConfigurationProperties(prefix = "cloudcold.agent.thread-pool.tool")
    public ThreadPoolTaskExecutor agentToolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("agent-tool-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean("longTermMemoryExecutor")
    @ConfigurationProperties(prefix = "cloudcold.agent.thread-pool.ltm")
    public ThreadPoolTaskExecutor longTermMemoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("ltm-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean("virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("vt-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
