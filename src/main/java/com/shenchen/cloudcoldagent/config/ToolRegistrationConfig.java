package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.tools.BaseTool;
import com.shenchen.cloudcoldagent.tools.skill.ExecuteSkillScriptTool;
import com.shenchen.cloudcoldagent.tools.skill.ListSkillResourcesTool;
import com.shenchen.cloudcoldagent.tools.skill.ReadSkillResourceTool;
import com.shenchen.cloudcoldagent.tools.skill.ReadSkillTool;
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
                .filter(tool -> !(tool instanceof ReadSkillTool))
                .filter(tool -> !(tool instanceof ReadSkillResourceTool))
                .filter(tool -> !(tool instanceof ListSkillResourcesTool))
                .sorted(Comparator.comparing(tool -> tool.getClass().getName()))
                .toList();
        return ToolCallbacks.from(sortedTools.toArray());
    }

}
