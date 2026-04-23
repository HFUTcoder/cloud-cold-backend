package com.shenchen.cloudcoldagent.skillworkflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class LoadSkillContentsNode {

    private final SkillService skillService;

    public LoadSkillContentsNode(SkillService skillService) {
        this.skillService = skillService;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        List<String> selectedSkills =
                (List<String>) state.value(SkillWorkflowStateKeys.SELECTED_SKILLS).orElse(List.of());
        Map<String, String> skillContents = new LinkedHashMap<>();
        Map<String, SkillResourceListVO> skillResources = new LinkedHashMap<>();
        for (String skillName : selectedSkills) {
            try {
                skillContents.put(skillName, skillService.readSkillContent(skillName));
                skillResources.put(skillName, skillService.listSkillResources(skillName));
            } catch (IOException ex) {
                throw new UncheckedIOException("读取 skill 失败: " + skillName, ex);
            }
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(SkillWorkflowStateKeys.SKILL_CONTENTS, skillContents);
        updates.put(SkillWorkflowStateKeys.SKILL_RESOURCES, skillResources);
        return CompletableFuture.completedFuture(updates);
    }
}
