package com.shenchen.cloudcoldagent.tools.skill;

import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceContentVO;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `ReadSkillResourceTool` 类型实现。
 */
@Component
public class ReadSkillResourceTool extends BaseTool {

    public static final String TOOL_NAME = "read_skill_resource";

    private final SkillService skillService;

    public ReadSkillResourceTool(SkillService skillService) {
        super(false);
        this.skillService = skillService;
    }

    @Tool(name = TOOL_NAME, description = "读取 skill 内部某个具体资源的内容，可按需读取 references 或 scripts 下的文件，也支持按行范围读取。")
    public String readSkillResource(@ToolParam(description = "skill 名称") String skillName,
                                    @ToolParam(description = "资源类型，仅支持 main/reference/script") String resourceType,
                                    @ToolParam(description = "资源相对路径；当 resourceType=main 时可传空，否则必须传 references/... 或 scripts/...") String resourcePath,
                                    @ToolParam(description = "起始行号，可为空，默认从第 1 行开始") Integer startLine,
                                    @ToolParam(description = "结束行号，可为空，默认读取到最后一行") Integer endLine) {
        String inputSummary = "skillName=%s, resourceType=%s, resourcePath=%s, startLine=%s, endLine=%s"
                .formatted(defaultText(skillName), defaultText(resourceType), defaultText(resourcePath),
                        startLine == null ? "无" : startLine, endLine == null ? "无" : endLine);
        logToolStart(TOOL_NAME, "input", inputSummary);

        try {
            SkillResourceContentVO result = skillService.readSkillResource(skillName, resourceType, resourcePath, startLine, endLine);
            String formattedResult = formatResult(result);
            logToolSuccess(TOOL_NAME, inputSummary, formattedResult);
            return formattedResult;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, inputSummary, e, "读取 skill 资源失败：");
        }
    }

    private String formatResult(SkillResourceContentVO result) {
        return """
                skillName: %s
                resourceType: %s
                resourcePath: %s
                startLine: %s
                endLine: %s
                truncated: %s
                content:
                %s
                """.formatted(
                defaultText(result.getSkillName()),
                defaultText(result.getResourceType()),
                defaultText(result.getResourcePath()),
                result.getStartLine(),
                result.getEndLine(),
                result.getTruncated(),
                defaultText(result.getContent())
        );
    }
}
