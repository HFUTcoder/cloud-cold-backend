package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.skill.SkillScriptExecuteRequest;
import com.shenchen.cloudcoldagent.model.vo.SkillContentVO;
import com.shenchen.cloudcoldagent.model.vo.SkillMetadataVO;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceContentVO;
import com.shenchen.cloudcoldagent.model.vo.SkillScriptExecutionVO;
import com.shenchen.cloudcoldagent.service.SkillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Skill 控制层，提供 skill 元数据、内容、资源和脚本执行接口。
 */
@RestController
@RequestMapping("/skill")
@ConditionalOnBean(SkillService.class)
public class SkillController {

    private final SkillService skillService;

    /**
     * 注入 skill 读取与执行所需的业务服务。
     *
     * @param skillService skill 业务服务。
     */
    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 查询当前系统可用的 skill 元数据列表。
     *
     * @return skill 元数据列表。
     */
    @GetMapping("/list")
    public BaseResponse<List<SkillMetadataVO>> listSkills() {
        return ResultUtils.success(skillService.listSkillMetadata());
    }

    /**
     * 查询指定 skill 的元数据信息。
     *
     * @param skillName skill 名称。
     * @return 对应的 skill 元数据。
     */
    @GetMapping("/meta/{skillName}")
    public BaseResponse<SkillMetadataVO> getSkillMetadata(@PathVariable String skillName) {
        return ResultUtils.success(skillService.getSkillMetadata(skillName));
    }

    /**
     * 读取某个 skill 的主内容，通常对应其 `SKILL.md`。
     *
     * @param skillName skill 名称。
     * @return skill 主内容。
     * @throws IOException 读取 skill 文件失败时抛出。
     */
    @GetMapping("/{skillName}")
    public BaseResponse<SkillContentVO> readSkill(@PathVariable String skillName) throws IOException {
        return ResultUtils.success(SkillContentVO.builder()
                .skillName(skillName)
                .content(skillService.readSkillContent(skillName))
                .build());
    }

    /**
     * 按资源类型和路径读取指定 skill 的附属资源内容。
     *
     * @param skillName skill 名称。
     * @param resourceType 资源类型。
     * @param resourcePath 资源相对路径。
     * @param startLine 起始行号。
     * @param endLine 结束行号。
     * @return skill 资源内容。
     * @throws IOException 读取资源失败时抛出。
     */
    @GetMapping("/resource")
    public BaseResponse<SkillResourceContentVO> readSkillResource(@RequestParam String skillName,
                                                                  @RequestParam String resourceType,
                                                                  @RequestParam(required = false) String resourcePath,
                                                                  @RequestParam(required = false) Integer startLine,
                                                                  @RequestParam(required = false) Integer endLine) throws IOException {
        return ResultUtils.success(skillService.readSkillResource(skillName, resourceType, resourcePath, startLine, endLine));
    }

    /**
     * 直接执行某个 skill 下的脚本入口。
     *
     * @param request 脚本执行请求体。
     * @return 脚本执行结果。
     * @throws IOException 读取脚本或执行脚本失败时抛出。
     */
    @PostMapping("/script/execute")
    public BaseResponse<SkillScriptExecutionVO> executeSkillScript(@RequestBody SkillScriptExecuteRequest request)
            throws IOException {
        ThrowUtils.throwIf(request == null
                        || request.getSkillName() == null || request.getSkillName().isBlank()
                        || request.getScriptPath() == null || request.getScriptPath().isBlank(),
                ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(skillService.executeSkillScript(
                request.getSkillName(),
                request.getScriptPath(),
                request.getArguments()
        ));
    }
}
