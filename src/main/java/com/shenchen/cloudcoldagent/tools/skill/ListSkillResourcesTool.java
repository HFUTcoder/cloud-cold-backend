package com.shenchen.cloudcoldagent.tools.skill;

import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceListVO;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `ListSkillResourcesTool` 类型实现。
 */
@Component
public class ListSkillResourcesTool extends BaseTool {

    private static final String TOOL_NAME = "list_skill_resources";

    private final SkillService skillService;

    public ListSkillResourcesTool(SkillService skillService) {
        super(false);
        this.skillService = skillService;
    }

    @Tool(name = "list_skill_resources", description = "列出某个 skill 下当前可读取的资源清单。 当你不知道 references 或 scripts 中具体有哪些文件时，先调用此工具，不要猜文件名。")
    public String listSkillResources(@ToolParam(description = "skill 名称") String skillName) {
        logToolStart(TOOL_NAME, "skillName", skillName);
        try {
            SkillResourceListVO result = skillService.listSkillResources(skillName);
            String formattedResult = formatResult(result);
            logToolSuccess(TOOL_NAME, skillName, formattedResult);
            return formattedResult;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, skillName, e, "列出 skill 资源失败：");
        }
    }

    private String formatResult(SkillResourceListVO result) {
        return """
                skillName: %s
                mainFile: %s
                references:
                %s
                scripts:
                %s
                """.formatted(
                defaultText(result.getSkillName()),
                defaultText(result.getMainFile()),
                formatList(result.getReferences()),
                formatList(result.getScripts())
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
