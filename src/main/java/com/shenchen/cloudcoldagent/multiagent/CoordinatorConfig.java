package com.shenchen.cloudcoldagent.multiagent;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.hitl.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.hitl.HitlResumeService;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
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
    private int maxWorkers = 5;

    /** 每个 Worker 最大推理轮次 */
    private int maxRoundsPerWorker = 5;

    /** 协调者最大轮次 */
    private int maxCoordinatorRounds = 5;

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
                maxWorkers, maxRoundsPerWorker,
                toolExecutor, virtualThreadExecutor
        );
    }

    /**
     * 协调者 Agent（复用 PlanExecuteAgent）。
     * <p>
     * 工具来源：
     * 1. commonTools - 基础工具（search, execute_skill_script）
     * 2. coordinatorTools - 协调者专用工具（dispatch_to_worker）
     */
    @Bean
    @ConditionalOnProperty(prefix = "cloudcold.multiagent.coordinator", name = "enabled", havingValue = "true")
    public PlanExecuteAgent coordinatorAgent(
            ChatModel chatModel,
            @Qualifier("commonTools") ToolCallback[] commonTools,
            @Qualifier("coordinatorTools") ToolCallback[] coordinatorTools,
            HitlExecutionService hitlExecutionService,
            HitlCheckpointService hitlCheckpointService,
            HitlResumeService hitlResumeService,
            SkillService skillService) {

        Executor toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 合并工具列表
        List<ToolCallback> allTools = new ArrayList<>();
        allTools.addAll(Arrays.asList(commonTools));
        allTools.addAll(Arrays.asList(coordinatorTools));

        log.info("========== 创建协调者 Agent ==========");
        log.info("commonTools 数量: {}", commonTools.length);
        log.info("coordinatorTools 数量: {}", coordinatorTools.length);
        log.info("总工具数: {}", allTools.size());
        for (ToolCallback tool : allTools) {
            log.info("  工具: {}", tool.getToolDefinition().name());
        }

        return PlanExecuteAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .maxRounds(maxCoordinatorRounds)
                .contextCharLimit(10000)
                .maxToolRetries(2)
                .toolConcurrency(3)
                .hitlExecutionService(hitlExecutionService)
                .hitlCheckpointService(hitlCheckpointService)
                .hitlResumeService(hitlResumeService)
                .skillService(skillService)
                .agentType("CoordinatorAgent")
                .toolExecutor(toolExecutor)
                .virtualThreadExecutor(virtualThreadExecutor)
                .build();
    }
}
