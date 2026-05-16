package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.tools.multiagent.WorkerDispatchTool;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import com.shenchen.cloudcoldagent.tools.common.SearchTool;
import com.shenchen.cloudcoldagent.tools.skill.ExecuteSkillScriptTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

/**
 * `ToolRegistrationConfig` 类型实现。
 */
@Configuration
public class ToolRegistrationConfig {

    @Bean("allTools")
    public ToolCallback[] allTools(List<BaseTool> baseTools) {
        List<BaseTool> sortedTools = baseTools.stream()
                .sorted(Comparator.comparing(tool -> tool.getClass().getName()))
                .toList();
        return ToolCallbacks.from(sortedTools.toArray());
    }

    @Bean("commonTools")
    public ToolCallback[] commonTools(SearchTool searchTool,
                                      ExecuteSkillScriptTool executeSkillScriptTool) {
        return ToolCallbacks.from(
                searchTool,
                executeSkillScriptTool
        );
    }

    /**
     * 多 Agent 协调者专用工具。
     * <p>
     * 包含 dispatch_to_worker 工具，用于将子任务派发给 Worker 执行。
     */
    @Bean("coordinatorTools")
    @ConditionalOnProperty(prefix = "cloudcold.multiagent.coordinator", name = "enabled", havingValue = "true")
    public ToolCallback[] coordinatorTools(WorkerDispatchTool workerDispatchTool) {
        return ToolCallbacks.from(workerDispatchTool);
    }

    /**
     * 多 Agent Worker 专用工具。
     * <p>
     * Worker 只需要搜索工具，不需要执行脚本等其他工具。
     */
    @Bean("workerTools")
    @ConditionalOnProperty(prefix = "cloudcold.multiagent.coordinator", name = "enabled", havingValue = "true")
    public ToolCallback[] workerTools(SearchTool searchTool,
                                      ExecuteSkillScriptTool executeSkillScriptTool) {
        return ToolCallbacks.from(searchTool, executeSkillScriptTool);
    }

}
