package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillArgumentSpec;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * `BuildSkillRuntimeContextNode` 类型实现。
 */
@Component
public class BuildSkillRuntimeContextNode {

    private final SkillService skillService;

    public BuildSkillRuntimeContextNode(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 处理 `apply` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        List<String> selectedSkills =
                (List<String>) state.value(SkillWorkflowStateKeys.SELECTED_SKILLS).orElse(List.of());
        Map<String, String> skillContents =
                (Map<String, String>) state.value(SkillWorkflowStateKeys.SKILL_CONTENTS).orElse(Map.of());
        Map<String, SkillResourceListVO> skillResources =
                (Map<String, SkillResourceListVO>) state.value(SkillWorkflowStateKeys.SKILL_RESOURCES).orElse(Map.of());

        List<SkillRuntimeContext> skillRuntimeContexts = new ArrayList<>();
        for (String skillName : selectedSkills) {
            if (skillName == null || skillName.isBlank()) {
                continue;
            }
            SkillResourceListVO resourceList = skillResources.get(skillName);
            String singleScriptPath = resolveSingleScriptPath(resourceList);
            skillRuntimeContexts.add(SkillRuntimeContext.builder()
                    .skillName(skillName)
                    .content(skillContents.get(skillName))
                    .resourceList(resourceList)
                    .hasExecutableScript(singleScriptPath != null)
                    .singleScriptPath(singleScriptPath)
                    .scriptArgumentSpecs(resolveArgumentSpecs(skillName, singleScriptPath))
                    .build());
        }

        return CompletableFuture.completedFuture(new LinkedHashMap<>(Map.of(
                SkillWorkflowStateKeys.SKILL_RUNTIME_CONTEXTS, skillRuntimeContexts
        )));
    }

    private String resolveSingleScriptPath(SkillResourceListVO resourceList) {
        if (resourceList == null || resourceList.getScripts() == null || resourceList.getScripts().size() != 1) {
            return null;
        }
        String scriptPath = resourceList.getScripts().get(0);
        return scriptPath == null || scriptPath.isBlank() ? null : scriptPath;
    }

    private Map<String, SkillArgumentSpec> resolveArgumentSpecs(String skillName, String scriptPath) {
        if (skillName == null || skillName.isBlank() || scriptPath == null || scriptPath.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, SkillArgumentSpec> argumentSpecs = skillService.resolveSkillArgumentSpecs(skillName, scriptPath);
            return argumentSpecs == null ? Map.of() : argumentSpecs;
        } catch (IOException ignored) {
            return Map.of();
        }
    }
}
