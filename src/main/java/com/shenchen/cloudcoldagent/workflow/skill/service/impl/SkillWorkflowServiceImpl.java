package com.shenchen.cloudcoldagent.workflow.skill.service.impl;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SkillWorkflowServiceImpl implements SkillWorkflowService {

    private final CompiledGraph skillWorkflowGraph;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SkillWorkflowServiceImpl(CompiledGraph skillWorkflowGraph,
                                    ChatModel chatModel,
                                    ObjectMapper objectMapper) {
        this.skillWorkflowGraph = skillWorkflowGraph;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
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

    @Override
    public <T> T executeStructuredOutput(List<Message> messages, Class<T> outputType) {
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("StructuredOutputAgent")
                    .model(chatModel)
                    .outputType(outputType)
                    .build();
            AssistantMessage response = agent.call(messages);
            String text = response == null ? null : response.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("structured output 返回为空");
            }
            return objectMapper.readValue(text, outputType);
        } catch (Exception e) {
            throw new RuntimeException("structured output 执行失败", e);
        }
    }
}
