package com.shenchen.cloudcoldagent.workflow.skill.service.impl;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SkillWorkflowServiceImpl implements SkillWorkflowService {

    private final CompiledGraph skillWorkflowGraph;
    private final ObjectMapper objectMapper;

    public SkillWorkflowServiceImpl(CompiledGraph skillWorkflowGraph, ObjectMapper objectMapper) {
        this.skillWorkflowGraph = skillWorkflowGraph;
        this.objectMapper = objectMapper;
    }

    @Override
    public SkillWorkflowResult preprocess(Long userId, String conversationId, String question) {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SkillWorkflowStateKeys.USER_ID, userId);
        initialState.put(SkillWorkflowStateKeys.CONVERSATION_ID, conversationId);
        initialState.put(SkillWorkflowStateKeys.USER_QUESTION, question);
        log.info("开始执行 skill workflow 预处理，conversationId={}, userId={}, questionLength={}",
                conversationId, userId, question == null ? 0 : question.length());

        try {
            OverAllState finalState = skillWorkflowGraph.invoke(initialState).orElse(new OverAllState(initialState));
            List<String> selectedSkills = normalizeStringList(
                    finalState.value(SkillWorkflowStateKeys.SELECTED_SKILLS).orElse(List.of())
            );
            List<SkillExecutionPlan> executionPlans = normalizeExecutionPlans(
                    finalState.value(SkillWorkflowStateKeys.EXECUTION_PLANS).orElse(List.of())
            );
            String enhancedQuestion = (String) finalState.value(SkillWorkflowStateKeys.ENHANCED_QUESTION)
                    .orElse(question);

            SkillWorkflowResult result = SkillWorkflowResult.builder()
                    .selectedSkills(selectedSkills)
                    .executionPlans(executionPlans)
                    .enhancedQuestion(enhancedQuestion)
                    .build();
            log.info("skill workflow 预处理完成，conversationId={}, selectedSkills={}, planSummary={}",
                    conversationId,
                    selectedSkills,
                    summarizePlans(executionPlans));
            return result;
        } catch (Exception ex) {
            log.warn("skill workflow 预处理失败，回退原始问题。conversationId={}, message={}",
                    conversationId, ex.getMessage(), ex);
            return SkillWorkflowResult.builder()
                    .selectedSkills(List.of())
                    .executionPlans(List.of())
                    .enhancedQuestion(question)
                    .build();
        }
    }

    private List<String> normalizeStringList(Object rawValue) {
        if (!(rawValue instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }
        return normalized;
    }

    private List<SkillExecutionPlan> normalizeExecutionPlans(Object rawValue) {
        if (!(rawValue instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<SkillExecutionPlan> normalized = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            normalized.add(objectMapper.convertValue(item, SkillExecutionPlan.class));
        }
        return normalized;
    }

    private String summarizePlans(List<SkillExecutionPlan> executionPlans) {
        if (executionPlans == null || executionPlans.isEmpty()) {
            return "plans=0";
        }
        return executionPlans.stream()
                .filter(plan -> plan != null && plan.getSkillName() != null)
                .map(plan -> "%s[selected=%s, executable=%s, blockingReason=%s, tool=%s]".formatted(
                        plan.getSkillName(),
                        Boolean.TRUE.equals(plan.getSelected()),
                        Boolean.TRUE.equals(plan.getExecutable()),
                        plan.getBlockingReason() == null ? "-" : plan.getBlockingReason(),
                        plan.getToolCallPlan() == null ? "-" : plan.getToolCallPlan().getToolName()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("plans=0");
    }
}
