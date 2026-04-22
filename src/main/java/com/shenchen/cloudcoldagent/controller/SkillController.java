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

@RestController
@RequestMapping("/skill")
@ConditionalOnBean(SkillService.class)
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping("/list")
    public BaseResponse<List<SkillMetadataVO>> listSkills() {
        return ResultUtils.success(skillService.listSkillMetadata());
    }

    @GetMapping("/meta/{skillName}")
    public BaseResponse<SkillMetadataVO> getSkillMetadata(@PathVariable String skillName) {
        return ResultUtils.success(skillService.getSkillMetadata(skillName));
    }

    @GetMapping("/{skillName}")
    public BaseResponse<SkillContentVO> readSkill(@PathVariable String skillName) throws IOException {
        return ResultUtils.success(SkillContentVO.builder()
                .skillName(skillName)
                .content(skillService.readSkillContent(skillName))
                .build());
    }

    @GetMapping("/resource")
    public BaseResponse<SkillResourceContentVO> readSkillResource(@RequestParam String skillName,
                                                                  @RequestParam String resourceType,
                                                                  @RequestParam(required = false) String resourcePath,
                                                                  @RequestParam(required = false) Integer startLine,
                                                                  @RequestParam(required = false) Integer endLine) throws IOException {
        return ResultUtils.success(skillService.readSkillResource(skillName, resourceType, resourcePath, startLine, endLine));
    }

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
