package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.tools.BaseTool;
import com.shenchen.cloudcoldagent.tools.common.SearchTool;
import com.shenchen.cloudcoldagent.tools.rag.KnowledgeScalarSearchTool;
import com.shenchen.cloudcoldagent.tools.rag.KnowledgeVectorSearchTool;
import com.shenchen.cloudcoldagent.tools.skill.ExecuteSkillScriptTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

/**
 * `ToolRegistrationConfig` 类型实现。
 */
@Configuration
public class ToolRegistrationConfig {

    /**
     * 处理 `all Tools` 对应逻辑。
     *
     * @param baseTools baseTools 参数。
     * @return 返回处理结果。
     */
    @Bean("allTools")
    public ToolCallback[] allTools(List<BaseTool> baseTools) {
        List<BaseTool> sortedTools = baseTools.stream()
                .sorted(Comparator.comparing(tool -> tool.getClass().getName()))
                .toList();
        return ToolCallbacks.from(sortedTools.toArray());
    }

    /**
     * 处理 `common Tools` 对应逻辑。
     *
     * @param searchTool searchTool 参数。
     * @param executeSkillScriptTool executeSkillScriptTool 参数。
     * @return 返回处理结果。
     */
    @Bean("commonTools")
    public ToolCallback[] commonTools(SearchTool searchTool,
                                      ExecuteSkillScriptTool executeSkillScriptTool) {
        return ToolCallbacks.from(
                searchTool,
                executeSkillScriptTool
        );
    }

}
