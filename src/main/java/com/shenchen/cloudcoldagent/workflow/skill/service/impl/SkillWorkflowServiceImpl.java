package com.shenchen.cloudcoldagent.workflow.skill.service.impl;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
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
            List<SkillRuntimeContext> selectedSkillContexts = normalizeSkillRuntimeContexts(
                    finalState.value(SkillWorkflowStateKeys.SKILL_RUNTIME_CONTEXTS).orElse(List.of())
            );

            SkillWorkflowResult result = SkillWorkflowResult.builder()
                    .selectedSkills(selectedSkills)
                    .selectedSkillContexts(selectedSkillContexts)
                    .build();
            log.info("skill workflow 预处理完成，conversationId={}, selectedSkills={}, selectedSkillContextCount={}",
                    conversationId,
                    selectedSkills,
                    selectedSkillContexts.size());
            return result;
        } catch (Exception ex) {
            log.warn("skill workflow 预处理失败，回退原始问题。conversationId={}, message={}",
                    conversationId, ex.getMessage(), ex);
            return SkillWorkflowResult.builder()
                    .selectedSkills(List.of())
                    .selectedSkillContexts(List.of())
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

    private List<SkillRuntimeContext> normalizeSkillRuntimeContexts(Object rawValue) {
        if (!(rawValue instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<SkillRuntimeContext> normalized = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            normalized.add(objectMapper.convertValue(item, SkillRuntimeContext.class));
        }
        return normalized;
    }
}
