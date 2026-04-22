package com.shenchen.cloudcoldagent.tools.skill;

import com.shenchen.cloudcoldagent.model.vo.SkillScriptExecutionVO;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecuteSkillScriptTool extends BaseTool {

    private static final String TOOL_NAME = "execute_skill_script";

    private final SkillService skillService;

    public ExecuteSkillScriptTool(SkillService skillService) {
        super(false);
        this.skillService = skillService;
    }

    @Tool(name = "execute_skill_script", description = "执行某个 skill 在 scripts 目录下的固定脚本。禁止传入任意代码，只允许传真实存在的 scripts/... 路径和结构化参数。")
    public String executeSkillScript(@ToolParam(description = "skill 名称") String skillName,
                                     @ToolParam(description = "脚本相对路径，必须是 list_skill_resources 返回过的 scripts/...") String scriptPath,
                                     @ToolParam(description = "脚本参数，使用结构化 JSON 对象，例如 {\"key\":\"value\"}") Map<String, Object> arguments) {
        String inputSummary = "skillName=%s, scriptPath=%s, arguments=%s"
                .formatted(defaultText(skillName), defaultText(scriptPath), arguments == null ? "{}" : arguments);
        logToolStart(TOOL_NAME, "input", inputSummary);
        try {
            SkillScriptExecutionVO result = skillService.executeSkillScript(skillName, scriptPath, arguments);
            String formattedResult = formatResult(result);
            logToolSuccess(TOOL_NAME, inputSummary, formattedResult);
            return formattedResult;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, inputSummary, e, "执行 skill script 失败：");
        }
    }

    private String formatResult(SkillScriptExecutionVO result) {
        return """
                skillName: %s
                scriptPath: %s
                arguments: %s
                engine: %s
                result:
                %s
                """.formatted(
                defaultText(result.getSkillName()),
                defaultText(result.getScriptPath()),
                result.getArguments() == null ? "{}" : result.getArguments(),
                defaultText(result.getEngine()),
                defaultText(result.getResult())
        );
    }
}
