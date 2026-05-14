package com.shenchen.cloudcoldagent.tools.skill;

import com.shenchen.cloudcoldagent.model.vo.skill.SkillScriptExecutionVO;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skill 脚本执行工具，负责调用 SkillService 执行某个 skill 下的固定脚本。
 */
@Component
public class ExecuteSkillScriptTool extends BaseTool {

    private static final String TOOL_NAME = "execute_skill_script";

    private final SkillService skillService;

    /**
     * 注入 skill 脚本执行所需的业务服务。
     *
     * @param skillService skill 脚本执行服务。
     */
    public ExecuteSkillScriptTool(SkillService skillService) {
        super(false);
        this.skillService = skillService;
    }

    /**
     * 执行指定 skill 的脚本入口，并将执行结果格式化为文本返回给 Agent。
     *
     * @param skillName skill 名称。
     * @param scriptPath skill 下脚本的相对路径。
     * @param arguments 脚本执行参数。
     * @return 供 Agent 消费的格式化执行结果。
     */
    @Tool(name = "execute_skill_script", description = "执行某个 skill 在 scripts 目录下的固定脚本。skillName、scriptPath 和 arguments 应优先来自前置 skill workflow 注入的完整 SKILL.md 与 execution hints。")
    public String executeSkillScript(@ToolParam(description = "skill 名称") String skillName,
                                     @ToolParam(description = "脚本相对路径，必须是该 skill 真实存在的 scripts/... 路径") String scriptPath,
                                     @ToolParam(description = "脚本参数，使用结构化 JSON 对象，例如 {\"key\":\"value\"}") Map<String, Object> arguments) {
        String inputSummary = "skillName=%s, scriptPath=%s, arguments=%s"
                .formatted(defaultText(skillName), defaultText(scriptPath), arguments == null ? "{}" : arguments);
        try {
            SkillScriptExecutionVO result = skillService.executeSkillScript(skillName, scriptPath, arguments);
            String formattedResult = formatResult(result);
            return formattedResult;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, inputSummary, e, "执行 skill script 失败：");
        }
    }

    /**
     * 将脚本执行结果转换为稳定的结构化文本，方便模型继续推理。
     *
     * @param result 脚本执行结果对象。
     * @return 结构化的文本结果。
     */
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
