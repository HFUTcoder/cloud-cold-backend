package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
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
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
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
import java.util.LinkedHashSet;
import java.util.List;
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
                            @Qualifier("commonTools") ToolCallback[] commonToolCallbacks) {
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
        log.info("skill workflow 处理完成，conversationId={}, selectedSkills={}, selectedSkillContextCount={}",
                conversationId,
                workflowResult == null ? List.of() : workflowResult.getSelectedSkills(),
                workflowResult == null || workflowResult.getSelectedSkillContexts() == null
                        ? 0
                        : workflowResult.getSelectedSkillContexts().size());
        KnowledgePreprocessResult knowledgePreprocessResult = knowledgePreprocessService.preprocess(
                userId,
                conversation,
                question
        );
        UserLongTermMemoryPreprocessResult longTermMemoryPreprocessResult =
                userLongTermMemoryPreprocessService.preprocess(userId, question);
        String effectiveQuestion = knowledgePreprocessResult.effectiveQuestion();
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(
                conversation,
                longTermMemoryPreprocessResult.runtimePrompt(),
                workflowResult == null ? List.of() : workflowResult.getSelectedSkillContexts()
        );
        log.info("准备路由到具体智能体，conversationId={}, mode={}, targetAgent={}, effectiveQuestionLength={}, knowledgePreprocessTriggered={}, knowledgeHitCount={}",
                conversationId,
                mode.getValue(),
                (mode == AgentModeEnum.FAST ? "SimpleReactAgent" : "PlanExecuteAgent"),
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
                agentFlux = planExecuteAgent.stream(userId, conversationId, effectiveQuestion, runtimeSystemPrompt, question);
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
                                            String longTermMemoryPrompt,
                                            List<SkillRuntimeContext> selectedSkillContexts) {
        List<String> segments = new ArrayList<>();
        if (StringUtils.isNotBlank(longTermMemoryPrompt)) {
            segments.add(longTermMemoryPrompt.trim());
        }
        String skillRuntimePrompt = buildSkillRuntimePrompt(selectedSkillContexts);
        if (StringUtils.isNotBlank(skillRuntimePrompt)) {
            segments.add(skillRuntimePrompt);
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

    private String buildSkillRuntimePrompt(List<SkillRuntimeContext> selectedSkillContexts) {
        if (selectedSkillContexts == null || selectedSkillContexts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Available Skill Context]\n")
                .append("以下 skills 已经为本轮选定，请在需要时完整阅读并严格遵循其约束。\n")
                .append("规则：\n")
                .append("1. 参数只能来自用户本轮问题原文，或 skill 中明确声明的默认值。\n")
                .append("2. 缺少必填参数时，应先向用户补问，不要自行编造。\n")
                .append("3. 不要猜测脚本路径、参数名或默认值。\n\n");
        for (SkillRuntimeContext context : selectedSkillContexts) {
            if (context == null || StringUtils.isBlank(context.getSkillName()) || StringUtils.isBlank(context.getContent())) {
                continue;
            }
            sb.append("=== SKILL: ").append(context.getSkillName().trim()).append(" ===\n");
            if (context.getResourceList() != null) {
                sb.append("resources:\n");
                sb.append("references: ")
                        .append(context.getResourceList().getReferences() == null ? "[]" : context.getResourceList().getReferences())
                        .append("\n");
                sb.append("scripts: ")
                        .append(context.getResourceList().getScripts() == null ? "[]" : context.getResourceList().getScripts())
                        .append("\n\n");
            }
            sb.append(context.getContent().trim()).append("\n");
            sb.append("=== END SKILL ===\n\n");
        }
        String prompt = sb.toString().trim();
        return prompt.isEmpty() ? null : prompt;
    }
}
