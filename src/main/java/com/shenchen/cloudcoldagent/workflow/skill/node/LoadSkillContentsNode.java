package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.skill.SkillResourceListVO;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.utils.StateValueUtils;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * `LoadSkillContentsNode` 类型实现。
 */
@Component
public class LoadSkillContentsNode {

    private final SkillService skillService;

    public LoadSkillContentsNode(SkillService skillService) {
        this.skillService = skillService;
    }

    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        List<String> selectedSkills =
                StateValueUtils.getValue(state, SkillWorkflowStateKeys.SELECTED_SKILLS, List.of());
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
