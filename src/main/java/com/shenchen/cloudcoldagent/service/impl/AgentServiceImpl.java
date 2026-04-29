package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.config.HitlProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.enums.AgentModeEnum;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillScriptExecutionRequest;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillToolCallPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
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

    private final ChatModel openAiChatModel;

    private final ChatMemoryRepository chatMemoryRepository;

    private final ChatConversationService chatConversationService;

    private final HitlExecutionService hitlExecutionService;

    private final HitlCheckpointService hitlCheckpointService;

    private final HitlResumeService hitlResumeService;

    private final HitlProperties hitlProperties;

    private final SkillWorkflowService skillWorkflowService;

    private final SkillService skillService;

    private final ObjectProvider<Advisor> advisorProvider;

    private final ToolCallback[] allToolCallbacks;

    private final ObjectMapper objectMapper;

    private List<Advisor> allAdvisors;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private PlanExecuteAgent planExecuteAgent;

    public AgentServiceImpl(ChatModel openAiChatModel,
                            ChatMemoryRepository chatMemoryRepository,
                            ChatConversationService chatConversationService,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            HitlProperties hitlProperties,
                            SkillWorkflowService skillWorkflowService,
                            SkillService skillService,
                            ObjectProvider<Advisor> advisorProvider,
                            @Qualifier("allTools") ToolCallback[] allToolCallbacks,
                            ObjectMapper objectMapper) {
        this.openAiChatModel = openAiChatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatConversationService = chatConversationService;
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.hitlProperties = hitlProperties;
        this.skillWorkflowService = skillWorkflowService;
        this.skillService = skillService;
        this.advisorProvider = advisorProvider;
        this.allToolCallbacks = allToolCallbacks;
        this.objectMapper = objectMapper;
    }

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
                .maxRounds(5)
                .maxToolRetries(5)
                .chatMemory(chatMemory)
                .contextCharLimit(5000)
                .hitlExecutionService(hitlExecutionService)
                .hitlCheckpointService(hitlCheckpointService)
                .hitlResumeService(hitlResumeService)
                .skillService(skillService)
                .hitlInterceptToolNames(resolveHitlInterceptToolNames())
                .build();
    }


    @Override
    public Flux<AgentStreamEvent> call(AgentCallRequest agentCallRequest, Long userId) {
        String question = agentCallRequest.getQuestion() == null ? "" : agentCallRequest.getQuestion();
        AgentModeEnum mode = agentCallRequest.getMode() == null ? AgentModeEnum.FAST : agentCallRequest.getMode();
        String conversationId = resolveConversationId(userId, agentCallRequest.getConversationId());
        log.info("开始处理智能体请求，userId={}, conversationId={}, mode={}, questionLength={}",
                userId, conversationId, mode.getValue(), question.length());
        chatConversationService.touchConversation(userId, conversationId);
        chatConversationService.generateTitleOnFirstMessage(userId, conversationId, question);
        SkillWorkflowResult workflowResult = skillWorkflowService.preprocess(userId, conversationId, question);
        log.info("skill workflow 处理完成，conversationId={}, selectedSkills={}, executablePlanCount={}, blockingPlanCount={}",
                conversationId,
                workflowResult == null ? List.of() : workflowResult.getSelectedSkills(),
                countExecutablePlans(workflowResult),
                countBlockingPlans(workflowResult));
        String blockingSkillReply = resolveBlockingSkillReply(workflowResult);
        if (blockingSkillReply != null) {
            persistShortCircuitExchange(conversationId, question, blockingSkillReply);
            log.info("命中 skill 缺参阻塞，直接返回前端提示，conversationId={}, reason=MISSING_REQUIRED_ARGUMENTS, replyLength={}",
                    conversationId,
                    blockingSkillReply.length());
            return Flux.just(AgentStreamEventFactory.finalAnswer(conversationId, blockingSkillReply));
        }
        String effectiveQuestion = workflowResult.getEnhancedQuestion();
        List<PlanTask> preferredPlan = buildPreferredPlan(workflowResult);
        log.info("准备路由到具体智能体，conversationId={}, mode={}, targetAgent={}, preferredPlanCount={}, effectiveQuestionLength={}",
                conversationId,
                mode.getValue(),
                (mode == AgentModeEnum.FAST ? "SimpleReactAgent" : "PlanExecuteAgent"),
                preferredPlan.size(),
                effectiveQuestion == null ? 0 : effectiveQuestion.length());

        switch (mode) {
            case FAST:
                return reactAgent.stream(conversationId, effectiveQuestion, null, question);
            case THINKING:
            case EXPERT:
                return planExecuteAgent.stream(conversationId, effectiveQuestion, null, question, preferredPlan);
            default:
                return reactAgent.stream(conversationId, effectiveQuestion, null, question);
        }
    }

    @Override
    public Flux<AgentStreamEvent> resume(String interruptId) {
        log.info("开始处理智能体恢复请求，interruptId={}", interruptId);
        HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(interruptId);
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        String agentType = checkpoint.getAgentType();
        log.info("恢复请求已定位到 checkpoint，interruptId={}, conversationId={}, agentType={}",
                interruptId,
                checkpoint.getConversationId(),
                agentType);
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

    private String resolveBlockingSkillReply(SkillWorkflowResult workflowResult) {
        if (workflowResult == null || workflowResult.getExecutionPlans() == null || workflowResult.getExecutionPlans().isEmpty()) {
            return null;
        }
        boolean hasExecutablePlan = workflowResult.getExecutionPlans().stream()
                .map(rawPlan -> objectMapper.convertValue(rawPlan, SkillExecutionPlan.class))
                .filter(plan -> plan != null && Boolean.TRUE.equals(plan.getSelected()))
                .anyMatch(plan -> Boolean.TRUE.equals(plan.getExecutable()));
        if (hasExecutablePlan) {
            return null;
        }
        return workflowResult.getExecutionPlans().stream()
                .map(rawPlan -> objectMapper.convertValue(rawPlan, SkillExecutionPlan.class))
                .filter(plan -> plan != null && Boolean.TRUE.equals(plan.getSelected()))
                .filter(plan -> !Boolean.TRUE.equals(plan.getExecutable()))
                .filter(plan -> "MISSING_REQUIRED_ARGUMENTS".equals(plan.getBlockingReason()))
                .map(SkillExecutionPlan::getBlockingUserMessage)
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse(null);
    }

    private long countExecutablePlans(SkillWorkflowResult workflowResult) {
        if (workflowResult == null || workflowResult.getExecutionPlans() == null) {
            return 0L;
        }
        return workflowResult.getExecutionPlans().stream()
                .map(rawPlan -> objectMapper.convertValue(rawPlan, SkillExecutionPlan.class))
                .filter(plan -> plan != null && Boolean.TRUE.equals(plan.getSelected()))
                .filter(plan -> Boolean.TRUE.equals(plan.getExecutable()))
                .count();
    }

    private long countBlockingPlans(SkillWorkflowResult workflowResult) {
        if (workflowResult == null || workflowResult.getExecutionPlans() == null) {
            return 0L;
        }
        return workflowResult.getExecutionPlans().stream()
                .map(rawPlan -> objectMapper.convertValue(rawPlan, SkillExecutionPlan.class))
                .filter(plan -> plan != null && Boolean.TRUE.equals(plan.getSelected()))
                .filter(plan -> !Boolean.TRUE.equals(plan.getExecutable()))
                .filter(plan -> StringUtils.isNotBlank(plan.getBlockingUserMessage()))
                .count();
    }

    private void persistShortCircuitExchange(String conversationId, String userQuestion, String assistantReply) {
        if (conversationId == null || conversationId.isBlank() || chatMemory == null) {
            return;
        }
        if (userQuestion != null && !userQuestion.isBlank()) {
            chatMemory.add(conversationId, new UserMessage(userQuestion));
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            chatMemory.add(conversationId, new AssistantMessage(assistantReply));
        }
    }
}
