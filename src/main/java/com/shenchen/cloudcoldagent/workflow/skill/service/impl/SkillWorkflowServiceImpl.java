package com.shenchen.cloudcoldagent.workflow.skill.service.impl;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SkillWorkflowServiceImpl implements SkillWorkflowService {

    private final CompiledGraph skillWorkflowGraph;

    public SkillWorkflowServiceImpl(CompiledGraph skillWorkflowGraph) {
        this.skillWorkflowGraph = skillWorkflowGraph;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillWorkflowResult preprocess(Long userId, String conversationId, String question) {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SkillWorkflowStateKeys.USER_ID, userId);
        initialState.put(SkillWorkflowStateKeys.CONVERSATION_ID, conversationId);
        initialState.put(SkillWorkflowStateKeys.USER_QUESTION, question);

        try {
            OverAllState finalState = skillWorkflowGraph.invoke(initialState).orElse(new OverAllState(initialState));
            List<String> selectedSkills =
                    (List<String>) finalState.value(SkillWorkflowStateKeys.SELECTED_SKILLS).orElse(List.of());
            List<SkillExecutionPlan> executionPlans =
                    (List<SkillExecutionPlan>) finalState.value(SkillWorkflowStateKeys.EXECUTION_PLANS).orElse(List.of());
            String enhancedQuestion = (String) finalState.value(SkillWorkflowStateKeys.ENHANCED_QUESTION)
                    .orElse(question);

            SkillWorkflowResult result = SkillWorkflowResult.builder()
                    .selectedSkills(selectedSkills)
                    .executionPlans(executionPlans)
                    .enhancedQuestion(enhancedQuestion)
                    .build();
            log.info("skill workflow 预处理完成，conversationId={}, selectedSkills={}, executionPlans={}",
                    conversationId, selectedSkills, executionPlans);
            return result;
        } catch (Exception ex) {
            log.warn("skill workflow 预处理失败，回退原始问题。conversationId={}, error={}",
                    conversationId, ex.getMessage(), ex);
            return SkillWorkflowResult.builder()
                    .selectedSkills(List.of())
                    .executionPlans(List.of())
                    .enhancedQuestion(question)
                    .build();
        }
    }
}
