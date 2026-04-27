package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.config.HitlProperties;
import com.shenchen.cloudcoldagent.constant.AgentModeConstant;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillScriptExecutionRequest;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillToolCallPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代理服务层实现
 *
 */
@Service
@Slf4j
public class AgentServiceImpl implements AgentService {

    @Autowired
    private ChatModel openAiChatModel;

    @Autowired
    private ChatMemoryRepository chatMemoryRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private HitlExecutionService hitlExecutionService;

    @Autowired
    private HitlCheckpointService hitlCheckpointService;

    @Autowired
    private HitlResumeService hitlResumeService;

    @Autowired
    private HitlProperties hitlProperties;

    @Autowired
    private SkillWorkflowService skillWorkflowService;

    @Autowired
    private ObjectProvider<Advisor> advisorProvider;

    @Autowired
    @Qualifier("allTools")
    private ToolCallback[] allToolCallbacks;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Advisor> allAdvisors;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private PlanExecuteAgent planExecuteAgent;

    @PostConstruct
    public void init() {
        allAdvisors = advisorProvider.orderedStream().toList();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
        reactAgent = SimpleReactAgent.builder()
                .name("ReactAgent")
                .chatModel(openAiChatModel)
                .tools(allToolCallbacks)
                .advisors(allAdvisors)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();
        planExecuteAgent = PlanExecuteAgent.builder()
                .agentType("PlanExecuteAgent")
                .chatModel(openAiChatModel)
                .tools(allToolCallbacks)
                .advisors(allAdvisors)
                .maxRounds(3)
                .maxToolRetries(3)
                .chatMemory(chatMemory)
                .contextCharLimit(5000)
                .hitlExecutionService(hitlExecutionService)
                .hitlCheckpointService(hitlCheckpointService)
                .hitlResumeService(hitlResumeService)
                .hitlInterceptToolNames(resolveHitlInterceptToolNames())
                .build();
    }


    @Override
    public Flux<AgentStreamEvent> call(AgentCallRequest agentCallRequest, Long userId) {
        String question = agentCallRequest.getQuestion() == null ? "" : agentCallRequest.getQuestion();
        String mode = agentCallRequest.getMode() == null ? AgentModeConstant.FAST : agentCallRequest.getMode();
        String conversationId = resolveConversationId(userId, agentCallRequest.getConversationId());
        chatConversationService.touchConversation(userId, conversationId);
        chatConversationService.generateTitleOnFirstMessage(userId, conversationId, question);
        SkillWorkflowResult workflowResult = skillWorkflowService.preprocess(userId, conversationId, question);
        String effectiveQuestion = workflowResult.getEnhancedQuestion();
        List<PlanTask> preferredPlan = buildPreferredPlan(workflowResult);

        switch (mode) {
            case AgentModeConstant.FAST:
                return reactAgent.stream(conversationId, effectiveQuestion, null, question);
            case AgentModeConstant.THINKING:
                return planExecuteAgent.stream(conversationId, effectiveQuestion, null, question, preferredPlan);
            default:
                return reactAgent.stream(conversationId, effectiveQuestion, null, question);
        }
    }

    @Override
    public Flux<AgentStreamEvent> resume(String interruptId) {
        HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(interruptId);
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        String agentType = checkpoint.getAgentType();
        if ("PlanExecuteAgent".equals(agentType) || agentType == null || agentType.isBlank()) {
            return planExecuteAgent.resume(interruptId);
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 agentType 暂不支持 resume: " + agentType);
    }

    private String resolveConversationId(Long userId, String rawConversationId) {
        if (rawConversationId == null || rawConversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空，请先创建会话");
        }
        return chatConversationService.normalizeConversationId(userId, rawConversationId);
    }

    private Set<String> resolveHitlInterceptToolNames() {
        if (!hitlProperties.isEnabled() || hitlProperties.getInterceptToolNames() == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(hitlProperties.getInterceptToolNames());
    }

    private List<PlanTask> buildPreferredPlan(SkillWorkflowResult workflowResult) {
        if (workflowResult == null || workflowResult.getExecutionPlans() == null || workflowResult.getExecutionPlans().isEmpty()) {
            return List.of();
        }
        List<PlanTask> tasks = new ArrayList<>();
        int order = 1;
        int index = 1;
        for (Object rawExecutionPlan : workflowResult.getExecutionPlans()) {
            SkillExecutionPlan executionPlan = objectMapper.convertValue(rawExecutionPlan, SkillExecutionPlan.class);
            if (executionPlan == null || !Boolean.TRUE.equals(executionPlan.getSelected())
                    || !Boolean.TRUE.equals(executionPlan.getExecutable())) {
                continue;
            }
            SkillToolCallPlan toolCallPlan = executionPlan.getToolCallPlan();
            if (toolCallPlan == null || toolCallPlan.getToolName() == null || toolCallPlan.getToolName().isBlank()) {
                continue;
            }
            SkillScriptExecutionRequest request = toolCallPlan.getRequest();
            if (request == null
                    || request.getSkillName() == null || request.getSkillName().isBlank()
                    || request.getScriptPath() == null || request.getScriptPath().isBlank()
                    || request.getArguments() == null) {
                continue;
            }
            Map<String, Object> toolArguments = new LinkedHashMap<>();
            toolArguments.put("skillName", request.getSkillName());
            toolArguments.put("scriptPath", request.getScriptPath());
            toolArguments.put("arguments", request.getArguments() == null ? Map.of() : request.getArguments());
            toolArguments.put("argumentSpecs", request.getArgumentSpecs() == null ? Map.of() : request.getArgumentSpecs());
            tasks.add(new PlanTask(
                    toolCallPlan.getToolName() + "_" + index++,
                    toolCallPlan.getToolName(),
                    toolArguments,
                    order,
                    toolCallPlan.getToolName()
            ));
        }
        return tasks;
    }
}
