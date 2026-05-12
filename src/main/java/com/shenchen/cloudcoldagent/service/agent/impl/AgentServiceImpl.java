package com.shenchen.cloudcoldagent.service.agent.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.config.properties.AgentProperties;
import com.shenchen.cloudcoldagent.config.properties.HitlProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.enums.AgentModeEnum;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.KnowledgePrompts;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.service.agent.AgentService;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.hitl.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.hitl.HitlResumeService;
import com.shenchen.cloudcoldagent.service.chat.ChatMemoryPendingImageBindingService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgePreprocessService;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.service.chat.UserConversationRelationService;
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
import java.util.concurrent.Executor;

/**
 * Agent 服务主实现，串联会话、skill、知识库、长期记忆和具体 Agent 执行器。
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

    private final AgentProperties agentProperties;

    private final SkillWorkflowService skillWorkflowService;

    private final KnowledgePreprocessService knowledgePreprocessService;

    private final UserLongTermMemoryPreprocessService userLongTermMemoryPreprocessService;

    private final SkillService skillService;

    private final UserConversationRelationService userConversationRelationService;

    private final ObjectProvider<Advisor> advisorProvider;

    private final ToolCallback[] commonToolCallbacks;

    private final Executor agentToolTaskExecutor;

    private final Executor virtualThreadExecutor;

    private List<Advisor> allAdvisors;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private PlanExecuteAgent planExecuteAgent;

    /**
     * 注入 Agent 主链路运行所需的依赖。
     *
     * @param openAiChatModel 默认对话模型。
     * @param chatMemoryRepository 对话记忆持久化仓库。
     * @param chatConversationService 会话业务服务。
     * @param chatMemoryPendingImageBindingService 待绑定知识库图片服务。
     * @param hitlExecutionService HITL 执行协调服务。
     * @param hitlCheckpointService HITL checkpoint 服务。
     * @param hitlResumeService HITL 恢复服务。
     * @param hitlProperties HITL 相关配置。
     * @param agentProperties Agent 运行配置。
     * @param skillWorkflowService skill 工作流服务。
     * @param knowledgePreprocessService 知识库预检索服务。
     * @param userLongTermMemoryPreprocessService 长期记忆预处理服务。
     * @param skillService skill 业务服务。
     * @param userConversationRelationService 用户会话归属服务。
     * @param advisorProvider advisor 提供器。
     * @param commonToolCallbacks 主工具池。
     * @param agentToolTaskExecutor 代理工具调用线程池。
     * @param virtualThreadExecutor 虚拟线程执行器。
     */
    public AgentServiceImpl(ChatModel openAiChatModel,
                            ChatMemoryRepository chatMemoryRepository,
                            ChatConversationService chatConversationService,
                            ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            HitlProperties hitlProperties,
                            AgentProperties agentProperties,
                            SkillWorkflowService skillWorkflowService,
                            KnowledgePreprocessService knowledgePreprocessService,
                            UserLongTermMemoryPreprocessService userLongTermMemoryPreprocessService,
                            SkillService skillService,
                            UserConversationRelationService userConversationRelationService,
                            ObjectProvider<Advisor> advisorProvider,
                            @Qualifier("commonTools") ToolCallback[] commonToolCallbacks,
                            @Qualifier("agentToolTaskExecutor") Executor agentToolTaskExecutor,
                            @Qualifier("virtualThreadExecutor") Executor virtualThreadExecutor) {
        this.openAiChatModel = openAiChatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatConversationService = chatConversationService;
        this.chatMemoryPendingImageBindingService = chatMemoryPendingImageBindingService;
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.hitlProperties = hitlProperties;
        this.agentProperties = agentProperties;
        this.skillWorkflowService = skillWorkflowService;
        this.knowledgePreprocessService = knowledgePreprocessService;
        this.userLongTermMemoryPreprocessService = userLongTermMemoryPreprocessService;
        this.skillService = skillService;
        this.userConversationRelationService = userConversationRelationService;
        this.advisorProvider = advisorProvider;
        this.commonToolCallbacks = commonToolCallbacks;
        this.agentToolTaskExecutor = agentToolTaskExecutor;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * 在 Bean 初始化完成后构建共享记忆对象以及两类 Agent 执行器实例。
     */
    @PostConstruct
    public void init() {
        allAdvisors = advisorProvider.orderedStream().toList();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(agentProperties.getMemory().getMaxMessages())
                .build();
        reactAgent = SimpleReactAgent.builder()
                .name(agentProperties.getReact().getName())
                .chatModel(openAiChatModel)
                .tools(commonToolCallbacks)
                .advisors(allAdvisors)
                .chatMemory(chatMemory)
                .maxRounds(agentProperties.getReact().getMaxRounds())
                .toolConcurrency(agentProperties.getReact().getToolConcurrency())
                .toolExecutor(agentToolTaskExecutor)
                .virtualThreadExecutor(virtualThreadExecutor)
                .systemPrompt(agentProperties.getReact().getSystemPrompt())
                .build();
        planExecuteAgent = PlanExecuteAgent.builder()
                .agentType(agentProperties.getPlan().getAgentType())
                .timezone(agentProperties.getTimezone())
                .toolBatchTimeoutSeconds(agentProperties.getPlan().getToolBatchTimeoutSeconds())
                .chatModel(openAiChatModel)
                .tools(commonToolCallbacks)
                .advisors(allAdvisors)
                .maxRounds(agentProperties.getPlan().getMaxRounds())
                .maxToolRetries(agentProperties.getPlan().getMaxToolRetries())
                .chatMemory(chatMemory)
                .contextCharLimit(agentProperties.getPlan().getContextCharLimit())
                .toolConcurrency(agentProperties.getPlan().getToolConcurrency())
                .hitlExecutionService(hitlExecutionService)
                .hitlCheckpointService(hitlCheckpointService)
                .hitlResumeService(hitlResumeService)
                .skillService(skillService)
                .hitlInterceptToolNames(resolveHitlInterceptToolNames())
                .toolExecutor(agentToolTaskExecutor)
                .virtualThreadExecutor(virtualThreadExecutor)
                .build();
    }


    /**
     * 执行一次完整的 Agent 调用主链路，包含会话校验、skill 预处理、知识库预检索、长期记忆召回和具体 Agent 路由。
     *
     * @param agentCallRequest Agent 调用请求。
     * @param userId 当前用户 id。
     * @return 供控制层转发给前端的 Agent 事件流。
     */
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
                (mode == AgentModeEnum.FAST ? agentProperties.getReact().getName() : agentProperties.getPlan().getAgentType()),
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
                List<SkillRuntimeContext> skillContexts = workflowResult == null
                        ? List.of()
                        : workflowResult.getSelectedSkillContexts();
                agentFlux = planExecuteAgent.stream(userId, conversationId, effectiveQuestion,
                        runtimeSystemPrompt, question, skillContexts);
                break;
            default:
                agentFlux = reactAgent.stream(userId, conversationId, effectiveQuestion, runtimeSystemPrompt, question);
                break;
        }
        return preAgentFlux == null ? agentFlux : Flux.concat(preAgentFlux, agentFlux);
    }

    /**
     * 根据 interruptId 恢复一次被 HITL 中断的 Agent 执行。
     *
     * @param interruptId 中断 id。
     * @param userId 当前用户 id。
     * @return 恢复执行后的 Agent 事件流。
     */
    @Override
    public Flux<AgentStreamEvent> resume(String interruptId, Long userId) {
        log.info("开始处理智能体恢复请求，userId={}, interruptId={}", userId, interruptId);
        HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(userId, interruptId);
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        String agentType = checkpoint.getAgentType();
        log.info("恢复请求已定位到 checkpoint，userId={}, interruptId={}, conversationId={}, agentType={}",
                userId,
                interruptId,
                checkpoint.getConversationId(),
                agentType);
        if (agentProperties.getPlan().getAgentType().equals(agentType) || agentType == null || agentType.isBlank()) {
            return planExecuteAgent.resume(interruptId, userId);
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 agentType 暂不支持 resume: " + agentType);
    }

    /**
     * 校验并标准化会话 id，确保当前用户对该会话拥有访问权限。
     *
     * @param userId 当前用户 id。
     * @param rawConversationId 原始会话 id。
     * @return 标准化后的会话 id。
     */
    private String resolveConversationId(Long userId, String rawConversationId) {
        if (rawConversationId == null || rawConversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空，请先创建会话");
        }
        return chatConversationService.normalizeConversationId(userId, rawConversationId);
    }

    /**
     * 根据配置解析当前需要被 HITL 拦截的工具名称集合。
     *
     * @return 需要进入人工确认流程的工具名称集合。
     */
    private Set<String> resolveHitlInterceptToolNames() {
        if (!hitlProperties.isEnabled() || hitlProperties.getInterceptToolNames() == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(hitlProperties.getInterceptToolNames());
    }

    /**
     * 拼装运行时 system prompt，将长期记忆、skill 上下文和知识库绑定提示合并给下游 Agent。
     *
     * @param conversation 当前会话。
     * @param longTermMemoryPrompt 长期记忆生成的提示词。
     * @param selectedSkillContexts skill 工作流选出的上下文。
     * @return 合并后的运行时 system prompt；若无内容则返回 null。
     */
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
            segments.add(KnowledgePrompts.buildBoundKnowledgeRuntimePrompt());
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join("\n\n", segments);
    }

    /**
     * 在进入 Agent 执行前，先将知识库命中的图片信息转换为预置 SSE 事件流。
     *
     * @param conversationId 当前会话 id。
     * @param knowledgePreprocessResult 知识库预处理结果。
     * @return 预置事件流；无命中图片时返回空流。
     */
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

    /**
     * 将选中的 skill 运行时上下文拼接成可注入模型的提示词文本。
     *
     * @param selectedSkillContexts 被工作流选中的 skill 上下文列表。
     * @return 拼装后的 skill runtime prompt；无可用上下文时返回 null。
     */
    private String buildSkillRuntimePrompt(List<SkillRuntimeContext> selectedSkillContexts) {
        if (selectedSkillContexts == null || selectedSkillContexts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SkillWorkflowPrompts.buildSelectedSkillRuntimeHeaderPrompt());
        for (SkillRuntimeContext context : selectedSkillContexts) {
            if (context == null || StringUtils.isBlank(context.getSkillName()) || StringUtils.isBlank(context.getContent())) {
                continue;
            }
            sb.append(SkillWorkflowPrompts.SKILL_HEADER_PREFIX).append(context.getSkillName().trim()).append(SkillWorkflowPrompts.SKILL_HEADER_SUFFIX);
            if (context.getResourceList() != null) {
                sb.append(SkillWorkflowPrompts.SKILL_RESOURCES_HEADER);
                sb.append(SkillWorkflowPrompts.SKILL_REFERENCES_LABEL)
                        .append(context.getResourceList().getReferences() == null ? SkillWorkflowPrompts.EMPTY_LIST_PLACEHOLDER : context.getResourceList().getReferences())
                        .append("\n");
                sb.append(SkillWorkflowPrompts.SKILL_SCRIPTS_LABEL)
                        .append(context.getResourceList().getScripts() == null ? SkillWorkflowPrompts.EMPTY_LIST_PLACEHOLDER : context.getResourceList().getScripts())
                        .append("\n\n");
            }
            sb.append(context.getContent().trim()).append("\n");
            sb.append(SkillWorkflowPrompts.SKILL_END_MARKER);
        }
        String prompt = sb.toString().trim();
        return prompt.isEmpty() ? null : prompt;
    }
}
