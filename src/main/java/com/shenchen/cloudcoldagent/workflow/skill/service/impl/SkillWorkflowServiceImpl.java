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

/**
 * Skill 工作流服务实现，负责驱动图编排执行并将最终状态转换成业务侧可消费的结果。
 */
@Service
@Slf4j
public class SkillWorkflowServiceImpl implements SkillWorkflowService {

    private final CompiledGraph skillWorkflowGraph;
    private final ObjectMapper objectMapper;

    /**
     * 注入 skill 工作流图和状态转换所需的对象映射器。
     *
     * @param skillWorkflowGraph 已编译的 skill 工作流图。
     * @param objectMapper 状态对象转换器。
     */
    public SkillWorkflowServiceImpl(CompiledGraph skillWorkflowGraph, ObjectMapper objectMapper) {
        this.skillWorkflowGraph = skillWorkflowGraph;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 skill 工作流图，对当前问题进行 skill 识别、资源加载和运行时上下文构建。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户原始问题。
     * @return 预处理后的 skill 结果；失败时回退为空结果。
     */
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

    /**
     * 将工作流状态中的原始列表值清洗成字符串列表。
     *
     * @param rawValue 工作流状态中的原始值。
     * @return 去空、去空白后的字符串列表。
     */
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

    /**
     * 将工作流状态中的原始上下文对象转换成 SkillRuntimeContext 列表。
     *
     * @param rawValue 工作流状态中的原始值。
     * @return 规范化后的 skill 运行时上下文列表。
     */
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
