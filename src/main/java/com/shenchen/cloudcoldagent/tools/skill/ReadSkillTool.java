package com.shenchen.cloudcoldagent.tools.skill;

import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceListVO;
import com.shenchen.cloudcoldagent.registry.SkillRegistry;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `ReadSkillTool` 类型实现。
 */
@Component
public class ReadSkillTool extends BaseTool {

    public static final String TOOL_NAME = "read_skill";

    private final SkillRegistry skillRegistry;
    private final SkillService skillService;

    public ReadSkillTool(SkillRegistry skillRegistry, SkillService skillService) {
        super(false);
        this.skillRegistry = skillRegistry;
        this.skillService = skillService;
    }

    @Tool(name = TOOL_NAME, description = "读取某个 skill 的完整内容，并附带该 skill 当前可用的 references/scripts 资源清单。当你需要使用某个 skill 的详细说明、步骤、约束或真实脚本路径时，优先调用此工具。")
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
            SkillResourceListVO resourceList = skillService.listSkillResources(skillName.trim());
            String result = buildReadResult(skillName.trim(), content, resourceList);
            logToolSuccess(TOOL_NAME, skillName.trim(), result);
            return result;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, skillName, e, "读取 skill 失败：");
        }
    }

    private String buildReadResult(String skillName, String content, SkillResourceListVO resourceList) {
        return """
                skillName: %s
                mainFile: %s
                references:
                %s
                scripts:
                %s
                content:
                %s
                """.formatted(
                skillName,
                resourceList == null ? "SKILL.md" : defaultText(resourceList.getMainFile()),
                formatList(resourceList == null ? null : resourceList.getReferences()),
                formatList(resourceList == null ? null : resourceList.getScripts()),
                defaultText(content)
        );
    }

    private String formatList(java.util.List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- 无";
        }
        return items.stream()
                .map(item -> "- " + defaultText(item))
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("- 无");
    }
}
