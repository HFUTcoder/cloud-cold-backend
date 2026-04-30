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

}
