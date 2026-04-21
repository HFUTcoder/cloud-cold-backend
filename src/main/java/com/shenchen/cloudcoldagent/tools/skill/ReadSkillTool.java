package com.shenchen.cloudcoldagent.tools.skill;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadSkillTool extends BaseTool {

    private static final String TOOL_NAME = "read_skill";

    private final SkillRegistry skillRegistry;

    public ReadSkillTool(SkillRegistry skillRegistry) {
        super(false);
        this.skillRegistry = skillRegistry;
    }

    @Tool(name = "read_skill", description = "读取某个 skill 的完整内容。当你需要使用某个 skill 的详细说明、步骤或约束时，先调用此工具读取该 skill。")
    public String readSkill(@ToolParam(description = "skill 名称") String skillName) {
        logToolStart(TOOL_NAME, "skillName", skillName);
        if (skillName == null || skillName.isBlank()) {
            return "请提供 skill 名称";
        }

        if (!skillRegistry.contains(skillName.trim())) {
            return "未找到对应的 skill：" + skillName.trim();
        }

        try {
            String content = skillRegistry.readSkillContent(skillName.trim());
            String result = buildReadResult(skillName.trim(), content);
            logToolSuccess(TOOL_NAME, skillName.trim(), result);
            return result;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, skillName, e, "读取 skill 失败：");
        }
    }

    private String buildReadResult(String skillName, String content) {
        return """
                skillName: %s
                content:
                %s
                """.formatted(skillName, defaultText(content));
    }
}
