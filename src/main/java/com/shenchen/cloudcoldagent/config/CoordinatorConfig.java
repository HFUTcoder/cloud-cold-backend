package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.agent.multiagent.worker.WorkerPool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Coordinator 模式配置。
 * <p>
 * 复用 PlanExecuteAgent 作为协调者，WorkerDispatchTool 作为任务派发工具。
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "cloudcold.multiagent.coordinator")
@Data
public class CoordinatorConfig {

    /** 是否启用 Coordinator 模式 */
    private boolean enabled = false;

    /** Worker 池最大容量 */
    private int maxWorkers = 6;

    /** 每个 Worker 最大推理轮次 */
    private int maxRoundsPerWorker = 5;

    /** 协调者最大轮次 */
    private int maxCoordinatorRounds = 5;

    /** Worker 池最小容量（预创建数量） */
    private int minWorkers = 6;

    @Bean
    @ConditionalOnProperty(prefix = "cloudcold.multiagent.coordinator", name = "enabled", havingValue = "true")
    public WorkerPool workerPool(
            ChatModel chatModel,
            @Qualifier("workerTools") ToolCallback[] workerTools) {

        Executor toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        List<ToolCallback> tools = Arrays.asList(workerTools);

        log.info("========== 创建 WorkerPool ==========");
        log.info("Worker 工具数: {}", tools.size());
        for (ToolCallback tool : tools) {
            log.info("  工具: {}", tool.getToolDefinition().name());
        }

        return new WorkerPool(
                chatModel, tools,
                minWorkers, maxWorkers, maxRoundsPerWorker,
                toolExecutor, virtualThreadExecutor
        );
    }
}
