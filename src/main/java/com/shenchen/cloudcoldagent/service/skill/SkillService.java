package com.shenchen.cloudcoldagent.service.skill;

import com.shenchen.cloudcoldagent.model.vo.skill.SkillMetadataVO;
import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceContentVO;
import com.shenchen.cloudcoldagent.model.vo.skill.SkillScriptExecutionVO;
import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceListVO;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillArgumentSpec;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * `SkillService` 接口定义。
 */
public interface SkillService {

    /**
     * 查询所有 skill 的轻量元数据列表，仅用于渐进式披露阶段。
     */
    List<SkillMetadataVO> listSkillMetadata();

    /**
     * 查询当前用户可见的全部 skill 元数据（项目技能 + 用户技能）。
     */
    List<SkillMetadataVO> listSkillMetadata(Long userId);

    /**
     * 查询单个 skill 的轻量元数据。
     */
    SkillMetadataVO getSkillMetadata(String skillName);

    /**
     * 读取 skill 详细内容。
     */
    String readSkillContent(String skillName) throws IOException;

    /**
     * 读取 skill 内部资源内容。
     */
    SkillResourceContentVO readSkillResource(String skillName, String resourceType, String resourcePath,
                                            Integer startLine, Integer endLine) throws IOException;

    /**
     * 列出 skill 内部可读取的资源清单。
     */
    SkillResourceListVO listSkillResources(String skillName) throws IOException;

    /**
     * 执行 skill 内部脚本。
     */
    SkillScriptExecutionVO executeSkillScript(String skillName, String scriptPath, Map<String, Object> arguments)
            throws IOException;

    /**
     * 从 SKILL.md 中解析指定脚本的参数定义，用于 HITL 等场景展示中文参数名。
     */
    Map<String, SkillArgumentSpec> resolveSkillArgumentSpecs(String skillName, String scriptPath) throws IOException;
}
