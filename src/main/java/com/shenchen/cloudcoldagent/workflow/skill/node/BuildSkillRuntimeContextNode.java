package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class BuildSkillRuntimeContextNode {

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
            skillRuntimeContexts.add(SkillRuntimeContext.builder()
                    .skillName(skillName)
                    .content(skillContents.get(skillName))
                    .resourceList(skillResources.get(skillName))
                    .build());
        }

        return CompletableFuture.completedFuture(new LinkedHashMap<>(Map.of(
                SkillWorkflowStateKeys.SKILL_RUNTIME_CONTEXTS, skillRuntimeContexts
        )));
    }
}
