package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class BuildEnhancedQuestionNode {

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String question = state.value(SkillWorkflowStateKeys.USER_QUESTION, String.class).orElse("");
        List<String> selectedSkills =
                (List<String>) state.value(SkillWorkflowStateKeys.SELECTED_SKILLS).orElse(List.of());
        Map<String, String> skillContents =
                (Map<String, String>) state.value(SkillWorkflowStateKeys.SKILL_CONTENTS).orElse(Map.of());
        List<SkillExecutionPlan> executionPlans =
                (List<SkillExecutionPlan>) state.value(SkillWorkflowStateKeys.EXECUTION_PLANS).orElse(List.of());
        return CompletableFuture.completedFuture(Map.of(
                SkillWorkflowStateKeys.ENHANCED_QUESTION,
                SkillWorkflowPrompts.buildEnhancedQuestion(selectedSkills, skillContents, executionPlans, question)
        ));
    }
}
