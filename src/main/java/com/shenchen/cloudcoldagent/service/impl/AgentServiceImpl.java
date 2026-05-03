package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.config.properties.HitlProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.enums.AgentModeEnum;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.service.ChatMemoryPendingImageBindingService;
import com.shenchen.cloudcoldagent.service.KnowledgePreprocessService;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.service.UserConversationRelationService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryPreprocessService;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;
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

    private final ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService;

    private final HitlExecutionService hitlExecutionService;

    private final HitlCheckpointService hitlCheckpointService;

    private final HitlResumeService hitlResumeService;

    private final HitlProperties hitlProperties;

    private final SkillWorkflowService skillWorkflowService;

    private final KnowledgePreprocessService knowledgePreprocessService;

    private final UserLongTermMemoryPreprocessService userLongTermMemoryPreprocessService;

    private final SkillService skillService;

    private final UserConversationRelationService userConversationRelationService;

    private final ObjectProvider<Advisor> advisorProvider;

    private final ToolCallback[] commonToolCallbacks;

    private final ObjectMapper objectMapper;

    private List<Advisor> allAdvisors;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private PlanExecuteAgent planExecuteAgent;

    public AgentServiceImpl(ChatModel openAiChatModel,
                            ChatMemoryRepository chatMemoryRepository,
                            ChatConversationService chatConversationService,
                            ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            HitlProperties hitlProperties,
                            SkillWorkflowService skillWorkflowService,
                            KnowledgePreprocessService knowledgePreprocessService,
                            UserLongTermMemoryPreprocessService userLongTermMemoryPreprocessService,
                            SkillService skillService,
                            UserConversationRelationService userConversationRelationService,
                            ObjectProvider<Advisor> advisorProvider,
                            @Qualifier("commonTools") ToolCallback[] commonToolCallbacks,
                            ObjectMapper objectMapper) {
        this.openAiChatModel = openAiChatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatConversationService = chatConversationService;
        this.chatMemoryPendingImageBindingService = chatMemoryPendingImageBindingService;
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.hitlProperties = hitlProperties;
        this.skillWorkflowService = skillWorkflowService;
        this.knowledgePreprocessService = knowledgePreprocessService;
        this.userLongTermMemoryPreprocessService = userLongTermMemoryPreprocessService;
        this.skillService = skillService;
        this.userConversationRelationService = userConversationRelationService;
        this.advisorProvider = advisorProvider;
        this.commonToolCallbacks = commonToolCallbacks;
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
                .tools(commonToolCallbacks)
                .advisors(allAdvisors)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();
        planExecuteAgent = PlanExecuteAgent.builder()
                .agentType("PlanExecuteAgent")
                .chatModel(openAiChatModel)
                .tools(commonToolCallbacks)
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
        var conversation = chatConversationService.getByConversationId(userId, conversationId);
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
        String workflowEnhancedQuestion = workflowResult.getEnhancedQuestion();
        KnowledgePreprocessResult knowledgePreprocessResult = knowledgePreprocessService.preprocess(
                userId,
                conversation,
                workflowEnhancedQuestion
        );
        UserLongTermMemoryPreprocessResult longTermMemoryPreprocessResult =
                userLongTermMemoryPreprocessService.preprocess(userId, question);
        String effectiveQuestion = knowledgePreprocessResult.effectiveQuestion();
        List<PlanTask> preferredPlan = buildPreferredPlan(workflowResult);
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(
                conversation,
                longTermMemoryPreprocessResult.runtimePrompt()
        );
        log.info("准备路由到具体智能体，conversationId={}, mode={}, targetAgent={}, preferredPlanCount={}, effectiveQuestionLength={}, knowledgePreprocessTriggered={}, knowledgeHitCount={}",
                conversationId,
                mode.getValue(),
                (mode == AgentModeEnum.FAST ? "SimpleReactAgent" : "PlanExecuteAgent"),
                preferredPlan.size(),
                effectiveQuestion == null ? 0 : effectiveQuestion.length(),
                knowledgePreprocessResult.retrievalTriggered(),
                knowledgePreprocessResult.retrievedChunks() == null ? 0 : knowledgePreprocessResult.retrievedChunks().size());
        log.info("进入Agent前最终用户问题文本，conversationId={}, mode={}\n{}",
                conversationId,
                mode.getValue(),
                effectiveQuestion == null ? "" : effectiveQuestion);
        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            log.info("进入Agent前运行时System Prompt，conversationId={}, mode={}\n{}",
                    conversationId,
                    mode.getValue(),
                    runtimeSystemPrompt);
        }
        chatMemoryPendingImageBindingService.registerPendingImages(
                conversationId,
                knowledgePreprocessResult.retrievedImages()
        );

        Flux<AgentStreamEvent> preAgentFlux = buildPreAgentFlux(
                conversationId,
                knowledgePreprocessResult
        );
        Flux<AgentStreamEvent> agentFlux;

        switch (mode) {
            case FAST:
                agentFlux = reactAgent.stream(userId, conversationId, effectiveQuestion, runtimeSystemPrompt, question);
                break;
            case THINKING:
            case EXPERT:
                agentFlux = planExecuteAgent.stream(userId, conversationId, effectiveQuestion, runtimeSystemPrompt, question, preferredPlan);
                break;
            default:
                agentFlux = reactAgent.stream(userId, conversationId, effectiveQuestion, runtimeSystemPrompt, question);
                break;
        }
        return preAgentFlux == null ? agentFlux : Flux.concat(preAgentFlux, agentFlux);
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
        Long userId = userConversationRelationService.getUserIdByConversationId(checkpoint.getConversationId());
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到会话归属用户，无法恢复执行");
        }
        if ("PlanExecuteAgent".equals(agentType) || agentType == null || agentType.isBlank()) {
            return planExecuteAgent.resume(interruptId, userId);
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

    private String buildRuntimeSystemPrompt(com.shenchen.cloudcoldagent.model.entity.ChatConversation conversation,
                                            String longTermMemoryPrompt) {
        List<String> segments = new ArrayList<>();
        if (StringUtils.isNotBlank(longTermMemoryPrompt)) {
            segments.add(longTermMemoryPrompt.trim());
        }
        if (conversation != null && conversation.getSelectedKnowledgeId() != null && conversation.getSelectedKnowledgeId() > 0) {
            segments.add("""
                    当前会话已绑定知识库。
                    如果上下文里已经提供知识库检索内容，请优先基于这些知识内容回答。
                    如果仍需要继续检索，请默认使用当前会话已绑定知识库，不要臆造其它知识库。
                    """.trim());
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join("\n\n", segments);
    }

    private Flux<AgentStreamEvent> buildPreAgentFlux(String conversationId,
                                                     KnowledgePreprocessResult knowledgePreprocessResult) {
        if (knowledgePreprocessResult == null
                || knowledgePreprocessResult.retrievedImages() == null
                || knowledgePreprocessResult.retrievedImages().isEmpty()) {
            return Flux.empty();
        }
        return Flux.just(AgentStreamEventFactory.knowledgeRetrieval(
                conversationId,
                knowledgePreprocessResult.retrievedImages()
        ));
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
