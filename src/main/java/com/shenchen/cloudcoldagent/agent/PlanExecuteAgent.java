package com.shenchen.cloudcoldagent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.CritiqueResult;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ExecutedTaskSnapshot;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanExecuteCallResult;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ResumeContext;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.TaskResult;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.PlanExecutePrompts;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.hitl.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.hitl.HitlResumeService;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.utils.PlanExecuteResumeUtils;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
import com.shenchen.cloudcoldagent.utils.JsonUtil;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillArgumentSpec;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;


/**
 * 思考模式 / 专家模式 Agent，采用 plan-execute 流程完成规划、执行、批判和总结。
 */
@Slf4j
public class PlanExecuteAgent extends BaseAgent {

    private static final ObjectMapper PLAN_OBJECT_MAPPER = new ObjectMapper();
    private static final String JSON_SCHEMA_KEY_TYPE = "type";
    private static final String JSON_SCHEMA_KEY_ITEMS = "items";
    private static final String JSON_SCHEMA_TYPE_ARRAY = "array";

    private static String toJsonStr(Object value) {
        try {
            return PLAN_OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，valueClass={}", value == null ? "null" : value.getClass().getSimpleName(), e);
            return "{}";
        }
    }

    private static Map<String, Object> parseJsonMap(String json) {
        try {
            return PLAN_OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("JSON 反序列化失败，jsonLength={}", json == null ? 0 : json.length(), e);
            return new LinkedHashMap<>();
        }
    }

    // plan-execute 总轮数
    private final int contextCharLimit;

    // 控制工具并发调用上限
    private final Semaphore toolSemaphore;

    // 工具重试次数
    private final int maxToolRetries;

    private final HitlExecutionService hitlExecutionService;

    private final HitlCheckpointService hitlCheckpointService;

    private final HitlResumeService hitlResumeService;

    private final Set<String> hitlInterceptToolNames;

    private final SkillService skillService;

    private final String agentType;

    private final String timezone;

    private final long toolBatchTimeoutSeconds;

    private final Executor toolExecutor;

    private final Executor virtualThreadExecutor;

    private final ChatClient chatClient;

    /**
     * 创建 `PlanExecuteAgent` 实例。
     *
     * @param chatModel chatModel 参数。
     * @param tools tools 参数。
     * @param advisors advisors 参数。
     * @param maxRounds maxRounds 参数。
     * @param contextCharLimit contextCharLimit 参数。
     * @param maxToolRetries maxToolRetries 参数。
     * @param chatMemory chatMemory 参数。
     * @param hitlExecutionService hitlExecutionService 参数。
     * @param hitlCheckpointService hitlCheckpointService 参数。
     * @param hitlResumeService hitlResumeService 参数。
     * @param skillService skillService 参数。
     * @param agentType agentType 参数。
     * @param toolConcurrency toolConcurrency 参数。
     * @param hitlInterceptToolNames hitlInterceptToolNames 参数。
     * @param toolExecutor 工具调用线程池。
     * @param virtualThreadExecutor 虚拟线程执行器，用于主循环解耦。
     */
    public PlanExecuteAgent(ChatModel chatModel,
                            List<ToolCallback> tools,
                            List<Advisor> advisors,
                            int maxRounds,
                            int contextCharLimit,
                            int maxToolRetries,
                            ChatMemory chatMemory,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            SkillService skillService,
                            String agentType,
                            String timezone,
                            long toolBatchTimeoutSeconds,
                            int toolConcurrency,
                            Set<String> hitlInterceptToolNames,
                            Executor toolExecutor,
                            Executor virtualThreadExecutor) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.contextCharLimit = contextCharLimit;
        this.maxToolRetries = maxToolRetries;
        this.toolSemaphore = new Semaphore(Math.max(1, toolConcurrency));
        this.toolExecutor = toolExecutor;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.skillService = skillService;
        this.agentType = agentType;
        this.timezone = timezone;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        this.hitlInterceptToolNames = hitlInterceptToolNames == null ? Set.of() : new LinkedHashSet<>(hitlInterceptToolNames);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (!this.advisors.isEmpty()) {
            builder.defaultAdvisors(this.advisors);
        }
        this.chatClient = builder.build();
    }

    /**
     * 创建 PlanExecuteAgent 构建器。
     *
     * @return 新的构建器实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * PlanExecuteAgent 构建器。
     */
    public static class Builder {
        private ChatModel chatModel;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<Advisor> advisors = new ArrayList<>();

        // 默认迭代5轮
        private int maxRounds = 5;

        // 默认context压缩阈值50000字符
        private int contextCharLimit = 50000;

        // 默认工具重试次数2次
        private int maxToolRetries = 2;

        private ChatMemory chatMemory;

        private HitlExecutionService hitlExecutionService;

        private HitlCheckpointService hitlCheckpointService;

        private HitlResumeService hitlResumeService;

        private SkillService skillService;

        private String agentType = "PlanExecuteAgent";

        private String timezone = "Asia/Shanghai";

        private long toolBatchTimeoutSeconds = 60;

        private int toolConcurrency = 3;

        private Set<String> hitlInterceptToolNames = new LinkedHashSet<>();

        private Executor toolExecutor;

        private Executor virtualThreadExecutor;

        /**
         * 处理 `chat Memory` 对应逻辑。
         *
         * @param chatMemory chatMemory 参数。
         * @return 返回处理结果。
         */
        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        /**
         * 处理 `hitl Execution Service` 对应逻辑。
         *
         * @param hitlExecutionService hitlExecutionService 参数。
         * @return 返回处理结果。
         */
        public Builder hitlExecutionService(HitlExecutionService hitlExecutionService) {
            this.hitlExecutionService = hitlExecutionService;
            return this;
        }

        /**
         * 处理 `hitl Checkpoint Service` 对应逻辑。
         *
         * @param hitlCheckpointService hitlCheckpointService 参数。
         * @return 返回处理结果。
         */
        public Builder hitlCheckpointService(HitlCheckpointService hitlCheckpointService) {
            this.hitlCheckpointService = hitlCheckpointService;
            return this;
        }

        /**
         * 处理 `hitl Resume Service` 对应逻辑。
         *
         * @param hitlResumeService hitlResumeService 参数。
         * @return 返回处理结果。
         */
        public Builder hitlResumeService(HitlResumeService hitlResumeService) {
            this.hitlResumeService = hitlResumeService;
            return this;
        }

        /**
         * 处理 `skill Service` 对应逻辑。
         *
         * @param skillService skillService 参数。
         * @return 返回处理结果。
         */
        public Builder skillService(SkillService skillService) {
            this.skillService = skillService;
            return this;
        }

        /**
         * 处理 `agent Type` 对应逻辑。
         *
         * @param agentType agentType 参数。
         * @return 返回处理结果。
         */
        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder timezone(String timezone) {
            this.timezone = timezone;
            return this;
        }

        public Builder toolBatchTimeoutSeconds(long toolBatchTimeoutSeconds) {
            this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
            return this;
        }

        /**
         * 处理 `tool Concurrency` 对应逻辑。
         *
         * @param toolConcurrency toolConcurrency 参数。
         * @return 返回处理结果。
         */
        public Builder toolConcurrency(int toolConcurrency) {
            this.toolConcurrency = toolConcurrency;
            return this;
        }

        /**
         * 处理 `hitl Intercept Tool Names` 对应逻辑。
         *
         * @param hitlInterceptToolNames hitlInterceptToolNames 参数。
         * @return 返回处理结果。
         */
        public Builder hitlInterceptToolNames(Set<String> hitlInterceptToolNames) {
            this.hitlInterceptToolNames = hitlInterceptToolNames == null ? new LinkedHashSet<>() : new LinkedHashSet<>(hitlInterceptToolNames);
            return this;
        }

        public Builder toolExecutor(Executor toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        public Builder virtualThreadExecutor(Executor virtualThreadExecutor) {
            this.virtualThreadExecutor = virtualThreadExecutor;
            return this;
        }

        /**
         * 处理 `chat Model` 对应逻辑。
         *
         * @param chatModel chatModel 参数。
         * @return 返回处理结果。
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 处理 `tools` 对应逻辑。
         *
         * @param tools tools 参数。
         * @return 返回处理结果。
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * 处理 `tools` 对应逻辑。
         *
         * @param tools tools 参数。
         * @return 返回处理结果。
         */
        public Builder tools(ToolCallback... tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(tools));
            return this;
        }

        /**
         * 处理 `advisors` 对应逻辑。
         *
         * @param advisors advisors 参数。
         * @return 返回处理结果。
         */
        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(advisors);
            return this;
        }

        /**
         * 处理 `advisors` 对应逻辑。
         *
         * @param advisors advisors 参数。
         * @return 返回处理结果。
         */
        public Builder advisors(Advisor... advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(advisors));
            return this;
        }

        /**
         * 处理 `max Rounds` 对应逻辑。
         *
         * @param maxRounds maxRounds 参数。
         * @return 返回处理结果。
         */
        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        /**
         * 处理 `context Char Limit` 对应逻辑。
         *
         * @param contextCharLimit contextCharLimit 参数。
         * @return 返回处理结果。
         */
        public Builder contextCharLimit(int contextCharLimit) {
            this.contextCharLimit = contextCharLimit;
            return this;
        }

        /**
         * 处理 `max Tool Retries` 对应逻辑。
         *
         * @param maxToolRetries maxToolRetries 参数。
         * @return 返回处理结果。
         */
        public Builder maxToolRetries(int maxToolRetries) {
            this.maxToolRetries = maxToolRetries;
            return this;
        }

        /**
         * 构建 `build` 对应结果。
         *
         * @return 返回处理结果。
         */
        public PlanExecuteAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");
            Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
            Objects.requireNonNull(virtualThreadExecutor, "virtualThreadExecutor must not be null");
            return new PlanExecuteAgent(chatModel, tools, advisors, maxRounds, contextCharLimit, maxToolRetries,
                    chatMemory, hitlExecutionService, hitlCheckpointService, hitlResumeService, skillService,
                    agentType, timezone, toolBatchTimeoutSeconds, toolConcurrency, hitlInterceptToolNames, toolExecutor, virtualThreadExecutor);
        }
    }

    /**
     * 以无显式用户上下文的方式同步调用 plan-execute Agent。
     *
     * @param question 用户问题。
     * @return 调用结果，包含最终回答或 HITL 中断事件。
     */
    public PlanExecuteCallResult call(String question) {
        return callInternal(null, null, question, null, question, List.of());
    }

    /**
     * 以带会话上下文的方式同步调用 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @return 调用结果，包含最终回答或 HITL 中断事件。
     */
    public PlanExecuteCallResult call(Long userId, String conversationId, String question) {
        return callInternal(userId, conversationId, question, null, question, List.of());
    }

    /**
     * 以带运行时 system prompt 的方式同步调用 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @return 调用结果，包含最终回答或 HITL 中断事件。
     */
    public PlanExecuteCallResult call(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, question, List.of());
    }

    /**
     * 以无显式用户上下文的方式流式执行 plan-execute Agent。
     *
     * @param question 用户问题。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return streamInternal(null, null, question, null, question, List.of());
    }

    // 带会话记忆
    /**
     * 以带会话上下文的方式流式执行 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question) {
        return streamInternal(userId, conversationId, question, null, question, List.of());
    }

    /**
     * 以带运行时 system prompt 的方式流式执行 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, question, List.of());
    }

    /**
     * 以完整参数形式流式执行 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @param skillRuntimeContexts 运行时 skill 上下文，由调用方传入而非共享实例字段。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question,
                                         String runtimeSystemPrompt, String memoryQuestion,
                                         List<SkillRuntimeContext> skillRuntimeContexts) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion, skillRuntimeContexts);
    }

    /**
     * 对外暴露的恢复入口，根据 interruptId 恢复一次被 HITL 暂停的执行链。
     *
     * @param interruptId 中断 id。
     * @param userId 当前用户 id。
     * @return 恢复执行后的事件流。
     */
    public Flux<AgentStreamEvent> resume(String interruptId, Long userId) {
        return resumeInternal(interruptId, userId);
    }

    /**
     * 执行 plan-execute 的流式主循环：规划、执行、批判、必要时压缩上下文，并最终产出总结。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> streamInternal(Long userId,
                                                 String conversationId,
                                                 String question,
                                                 String runtimeSystemPrompt,
                                                 String memoryQuestion,
                                                 List<SkillRuntimeContext> skillRuntimeContexts) {
        boolean useMemory = useMemory(conversationId);

        OverallState state = new OverallState(userId, conversationId, question);
        state.setSkillRuntimeContexts(skillRuntimeContexts == null ? List.of() : skillRuntimeContexts);

        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            state.add(new SystemMessage(runtimeSystemPrompt));
        }

        // 加载历史记忆到上下文messages中
        if (useMemory) {
            List<Message> history = getMemoryMessages(conversationId);
            if (!CollectionUtils.isEmpty(history)) {
                history.forEach(state::add);
            }
        }

        // 当前用户问题
        state.add(new UserMessage(question));

        // 当前问题存入memory
        if (useMemory) {
            addUserMemory(conversationId, memoryQuestion == null ? question : memoryQuestion);
        }

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        AtomicBoolean interruptedByHitl = new AtomicBoolean(false);
        hasSentFinalResult.set(false);

        // 收集最终答案，存储memory
        StringBuilder finalAnswerBuffer = new StringBuilder();

        virtualThreadExecutor.execute(() -> {
            try {
                while (maxRounds <= 0 || state.getRound() < maxRounds) {
                    state.nextRound();
                    log.info("开始执行 Plan-Execute 第 {} 轮，conversationId={}",
                            state.getRound(),
                            state.getConversationId());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    List<PlanTask> plan = generateAndAddPlan(state);
                    log.info("本轮执行计划已生成，conversationId={}, round={}, taskCount={}, summary={}",
                            state.getConversationId(),
                            state.getRound(),
                            plan.size(),
                            summarizePlan(plan));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty()) {
                        log.info("本轮未生成可执行任务，直接进入总结阶段。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "execution", "Execution", "当前无需执行工具任务，进入总结阶段。");
                        break;
                    }

                    Map<String, TaskResult> results = executePlan(plan, state);
                    emitStep(sink, state.getConversationId(), "task", "Task Result", renderTaskResultsForDisplay(results));

                    if (state.getInterruptedCheckpoint() != null) {
                        enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                        interruptedByHitl.set(true);
                        sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                        hasSentFinalResult.set(true);
                        sink.tryEmitComplete();
                        return;
                    }

                    LoopAction action = handleCritique(state);
                    if (action == LoopAction.SUMMARIZE) {
                        log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
                        break;
                    }
                    if (action == LoopAction.ASK_USER) {
                        log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "需要用户补充信息。");
                        break;
                    }
                    emitStep(sink, state.getConversationId(), "critique", "Critique Feedback",
                            state.getLastCritique() != null ? state.getLastCritique().feedback() : "");
                }
                if (state.getRound() == maxRounds) {
                    log.info("Plan-Execute 达到最大轮次限制，强制进入总结阶段。conversationId={}, round={}",
                            state.getConversationId(),
                            state.getRound());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute", "达到最大轮次，强制进入总结阶段。");
                }

                // 5.总结输出
                emitStep(sink, state.getConversationId(), "final", "Final Answer", "正在生成最终答案...");
                streamSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
                return;
            } catch (Exception e) {
                if (!hasSentFinalResult.get()) {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(e);
                }
            }
        });

        return sink.asFlux()
                .doOnCancel(() -> hasSentFinalResult.set(true))
                .doFinally(signalType -> {
                    if (interruptedByHitl.get()) {
                        log.info("Plan-Execute 执行链已因 HITL 中断，等待用户处理。conversationId={}", conversationId);
                        return;
                    }
                    log.info("Plan-Execute 流式执行结束，conversationId={}, finalAnswerLength={}",
                            conversationId,
                            finalAnswerBuffer.length());
                });
    }

    /**
     * 向前端发送一条 thinking step 事件，用于展示当前执行阶段。
     *
     * @param sink 当前事件流 sink。
     * @param conversationId 会话 id。
     * @param type 阶段类型。
     * @param title 阶段标题。
     * @param content 阶段内容。
     */
    private void emitStep(Sinks.Many<AgentStreamEvent> sink, String conversationId, String type, String title, String content) {
        if (sink == null || StringUtils.isBlank(content)) {
            return;
        }
        sink.tryEmitNext(AgentStreamEventFactory.thinkingStep(conversationId, type, title, content));
    }

    /**
     * 将执行计划渲染为适合前端展示的文本。
     *
     * @param plan 规划阶段产出的任务列表。
     * @return 可读性较强的计划文本。
     */
    private String renderPlanForDisplay(List<PlanTask> plan) {
        if (plan == null || plan.isEmpty()) {
            return "未生成可执行任务。";
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .map(task -> "- taskId: %s, order: %s, toolName: %s, summary: %s, arguments: %s".formatted(
                        task.id(),
                        task.order(),
                        defaultTaskValue(task.toolName()),
                        defaultTaskValue(task.summary()),
                        task.arguments() == null ? "{}" : toJsonStr(task.arguments())))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 将本轮任务执行结果渲染为适合前端展示的文本。
     *
     * @param results 本轮任务执行结果。
     * @return 可读性较强的执行结果文本。
     */
    private String renderTaskResultsForDisplay(Map<String, TaskResult> results) {
        if (results == null || results.isEmpty()) {
            return "本轮无工具执行结果。";
        }
        AtomicInteger stepIndex = new AtomicInteger(1);
        return results.values().stream()
                .sorted(Comparator.comparing(TaskResult::taskId, Comparator.nullsLast(String::compareTo)))
                .map(result -> {
                    int idx = stepIndex.getAndIncrement();
                    if (result.success()) {
                        String output = StringUtils.defaultIfBlank(result.output(), "未返回有效内容");
                        return "- 步骤 %s 执行结果：%s".formatted(idx, output);
                    }
                    String error = StringUtils.defaultIfBlank(result.error(), "执行失败");
                    return "- 步骤 %s 执行失败：%s".formatted(idx, error);
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 以完整参数形式同步执行 plan-execute 主链路。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return 调用结果，包含最终回答或 HITL 中断事件。
     */
    public PlanExecuteCallResult call(Long userId, String conversationId, String question, String runtimeSystemPrompt,
                                      String memoryQuestion, List<SkillRuntimeContext> skillRuntimeContexts) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion, skillRuntimeContexts);
    }

    /**
     * plan-execute 的同步执行主循环，负责规划、批判、上下文压缩和最终总结。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return 调用结果，包含最终回答或 HITL 中断事件。
     */
    public PlanExecuteCallResult callInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt,
                                              String memoryQuestion, List<SkillRuntimeContext> skillRuntimeContexts) {

        boolean useMemory = useMemory(conversationId);

        OverallState state = new OverallState(userId, conversationId, question);
        state.setSkillRuntimeContexts(skillRuntimeContexts == null ? List.of() : skillRuntimeContexts);

        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            state.add(new SystemMessage(runtimeSystemPrompt));
        }

        if (useMemory) {
            List<Message> history = getMemoryMessages(conversationId);
            if (!CollectionUtils.isEmpty(history)) {
                history.forEach(state::add);
            }
        }

        state.add(new UserMessage(question));

        if (useMemory) {
            addUserMemory(conversationId, memoryQuestion == null ? question : memoryQuestion);
        }

        while (maxRounds <= 0 || state.getRound() < maxRounds) {
            state.nextRound();
            log.info("开始执行 Plan-Execute 第 {} 轮，conversationId={}",
                    state.getRound(),
                    state.getConversationId());

            List<PlanTask> plan = generateAndAddPlan(state);
            log.info("本轮执行计划已生成，conversationId={}, round={}, taskCount={}, summary={}",
                    state.getConversationId(),
                    state.getRound(),
                    plan.size(),
                    summarizePlan(plan));

            if (plan.isEmpty()) {
                log.info("本轮未生成可执行任务，直接进入总结阶段。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }

            Map<String, TaskResult> results = executePlan(plan, state);
            if (state.getInterruptedCheckpoint() != null) {
                enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                return new PlanExecuteCallResult(null,
                        buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
            }

            LoopAction action = handleCritique(state);
            if (action == LoopAction.SUMMARIZE) {
                log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }
            if (action == LoopAction.ASK_USER) {
                log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }
        }
        if (state.getRound() == maxRounds) {
            log.info("Plan-Execute 达到最大轮次限制，强制进入总结阶段。conversationId={}, round={}",
                    state.getConversationId(),
                    state.getRound());
        }

        return new PlanExecuteCallResult(summarize(state), null);
    }

    /**
     * Plan-Execute 单轮循环结果。
     */
    private enum LoopAction {
        CONTINUE,
        SUMMARIZE,
        ASK_USER
    }

    /**
     * 生成当前轮的执行计划并写入 state。
     *
     * @param state 当前执行状态。
     * @return 清洗后的计划任务列表。
     */
    private List<PlanTask> generateAndAddPlan(OverallState state) {
        List<PlanTask> plan = sanitizePlan(generatePlan(state), state);
        state.add(new AssistantMessage(EXECUTION_PLAN_HEADER + plan));
        return plan;
    }

    /**
     * 执行批判阶段，将反馈写入 state，必要时压缩上下文。
     *
     * @param state 当前执行状态。
     * @return 批判后的循环决策。
     */
    private LoopAction handleCritique(OverallState state) {
        CritiqueResult critique = critique(state);
        state.lastCritique = critique;
        String critiqueText = "【Critique Feedback】\n" + critique.feedback();

        if (critique.passed() || CritiqueResult.ACTION_SUMMARIZE.equals(critique.action())) {
            return LoopAction.SUMMARIZE;
        }

        if (CritiqueResult.ACTION_ASK_USER.equals(critique.action())) {
            state.add(new AssistantMessage(critiqueText));
            return LoopAction.ASK_USER;
        }

        state.add(new AssistantMessage(critiqueText));
        compressIfNeeded(state);
        return LoopAction.CONTINUE;
    }

    /**
     * 调用模型为当前轮生成结构化执行计划。
     *
     * @param state 当前执行状态。
     * @return 结构化任务列表；解析失败时返回空列表。
     */
    private List<PlanTask> generatePlan(OverallState state) {
        String toolDesc = renderToolDescriptions();
        List<SkillRuntimeContext> skillContexts = state.getSkillRuntimeContexts();
        if (!skillContexts.isEmpty()) {
            toolDesc = enrichToolDescriptionsWithSkillContexts(toolDesc, skillContexts);
        }
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        String planPrompt = PlanExecutePrompts.formatPlanPrompt(
                LocalDateTime.now(ZoneId.of(timezone)),
                state.getRound(),
                toolDesc,
                renderExecutedTaskHistory(state),
                converter.getFormat()
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(planPrompt),
                new UserMessage(PlanExecutePrompts.buildConversationHistoryUserPrompt(renderMessages(state.getMessages())))
        ));

        String json = chatModel.call(prompt).getResult().getOutput().getText();
        if (StringUtils.isBlank(json)) {
            log.warn("执行计划生成结果为空，降级为空计划。conversationId={}, round={}",
                    state.getConversationId(),
                    state.getRound());
            return List.of();
        }

        String raw = json.trim();
        try {
            String normalizedPlanJson = normalizePlanJson(raw);
            return converter.convert(normalizedPlanJson);
        } catch (Exception ex) {
            log.warn("执行计划首次解析失败，尝试修复 JSON。conversationId={}, round={}, error={}",
                    state.getConversationId(),
                    state.getRound(),
                    ex.getMessage());
            try {
                String repaired = JsonUtil.fixJson(raw);
                String normalizedPlanJson = normalizePlanJson(repaired);
                List<PlanTask> plan = converter.convert(normalizedPlanJson);
                log.info("JSON 修复后计划解析成功。conversationId={}, round={}",
                        state.getConversationId(), state.getRound());
                return plan;
            } catch (Exception repairEx) {
                log.warn("JSON 修复后计划仍然解析失败，降级为空计划。conversationId={}, round={}, rawSnippet={}",
                        state.getConversationId(),
                        state.getRound(),
                        abbreviate(raw, 400),
                        repairEx);
                return List.of();
            }
        }
    }

    /**
     * 将当前可用的 skill 上下文注入工具描述，让 LLM 生成计划时直接引用正确的 skillName 和 scriptPath。
     */
    private String enrichToolDescriptionsWithSkillContexts(String toolDesc, List<SkillRuntimeContext> skillContexts) {
        StringBuilder sb = new StringBuilder(toolDesc.trim());
        sb.append("\n\n【当前可用的 Skill 上下文 —— 调用 execute_skill_script 时必须严格按此填写 skillName 和 scriptPath】\n");
        for (SkillRuntimeContext ctx : skillContexts) {
            sb.append("- skillName: \"").append(ctx.getSkillName()).append("\"");
            if (ctx.getSingleScriptPath() != null) {
                sb.append(", scriptPath: \"").append(ctx.getSingleScriptPath()).append("\"");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 兼容不同输出形态，将模型返回的计划 JSON 规范化成数组结构。
     *
     * @param raw 模型返回的原始 JSON 文本。
     * @return 规范化后的 JSON 数组文本。
     * @throws Exception 解析 JSON 失败时抛出。
     */
    private String normalizePlanJson(String raw) throws Exception {
        if (StringUtils.isBlank(raw)) {
            return "[]";
        }
        JsonNode root = PLAN_OBJECT_MAPPER.readTree(raw);
        if (root == null || root.isNull()) {
            return "[]";
        }
        if (root.isArray()) {
            return PLAN_OBJECT_MAPPER.writeValueAsString(root);
        }
        if (root.isObject()) {
            JsonNode typeNode = root.get(JSON_SCHEMA_KEY_TYPE);
            JsonNode itemsNode = root.get(JSON_SCHEMA_KEY_ITEMS);
            if (typeNode != null
                    && typeNode.isTextual()
                    && JSON_SCHEMA_TYPE_ARRAY.equalsIgnoreCase(typeNode.asText())
                    && itemsNode != null) {
                if (itemsNode.isArray()) {
                    return PLAN_OBJECT_MAPPER.writeValueAsString(itemsNode);
                }
                return PLAN_OBJECT_MAPPER.writeValueAsString(List.of(itemsNode));
            }
            return PLAN_OBJECT_MAPPER.writeValueAsString(List.of(root));
        }
        return "[]";
    }

    /**
     * 过滤非法任务并对结构化参数做一次兜底修复。
     *
     * @param plan 原始计划列表。
     * @param state 当前执行状态。
     * @return 清洗后的任务列表。
     */
    private static final Set<String> NO_TOOL_SENTINELS =
            Set.of("none", "null", "无", "-", "no_tool", "noop", "nop");

    private List<PlanTask> sanitizePlan(List<PlanTask> plan, OverallState state) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .filter(task -> StringUtils.isNotBlank(task.id()) && StringUtils.isNotBlank(task.toolName()))
                .filter(task -> !NO_TOOL_SENTINELS.contains(task.toolName().strip().toLowerCase()))
                .map(task -> repairStructuredToolTask(task, state))
                .toList();
    }


    /**
     * 执行一轮计划中的全部任务，按 order 串行、同 order 内按配置并发。
     *
     * @param plan 当前轮计划任务。
     * @param state 当前执行状态。
     * @return 任务 id 到执行结果的映射。
     */
    private Map<String, TaskResult> executePlan(List<PlanTask> plan, OverallState state) {

        Map<String, TaskResult> results = new LinkedHashMap<>();

        // 按 order 分组：order 相同的 task 可并行
        Map<Integer, List<PlanTask>> grouped =
                plan.stream().collect(Collectors.groupingBy(PlanTask::order));

        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();

        // 按 order 顺序执行（不同 order 串行）
        for (Integer order : new TreeSet<>(grouped.keySet())) {

            List<PlanTask> tasks = grouped.get(order);
            List<PlanTask> nonHitlTasks = tasks == null
                    ? List.of()
                    : tasks.stream()
                    .filter(task -> !requiresHitlApproval(task, state))
                    .toList();
            List<PlanTask> hitlTasks = tasks == null
                    ? List.of()
                    : tasks.stream()
                    .filter(task -> requiresHitlApproval(task, state))
                    .toList();
            log.info("开始执行 order={} 的任务组，conversationId={}, round={}, taskCount={}, summary={}",
                    order,
                    state.getConversationId(),
                    state.getRound(),
                    tasks == null ? 0 : tasks.size(),
                    summarizePlan(tasks));

            for (ExecutedTaskMergeResult mergeResult : executeTaskBatch(nonHitlTasks, state)) {
                mergeTaskResult(state, results, accumulatedResults, mergeResult);
            }
            if (state.getInterruptedCheckpoint() != null) {
                break;
            }

            for (PlanTask hitlTask : hitlTasks) {
                ExecutedTaskMergeResult mergeResult = executeTaskForMerge(hitlTask, state);
                mergeTaskResult(state, results, accumulatedResults, mergeResult);
                if (state.getInterruptedCheckpoint() != null) {
                    break;
                }
            }
            log.info("order={} 的任务组执行完成，conversationId={}, round={}, interrupted={}",
                    order,
                    state.getConversationId(),
                    state.getRound(),
                    state.getInterruptedCheckpoint() != null);

            if (state.getInterruptedCheckpoint() != null) {
                break;
            }
        }

        return results;
    }

    /**
     * 并发执行同一批次中无需 HITL 的任务。
     *
     * @param tasks 当前批次任务。
     * @param state 当前执行状态。
     * @return 批量任务的合并结果列表。
     */
    private List<ExecutedTaskMergeResult> executeTaskBatch(List<PlanTask> tasks, OverallState state) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<ExecutedTaskMergeResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> executeTaskForMerge(task, state),
                        toolExecutor
                ))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(toolBatchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("批量任务执行超时（{}s），取消未完成任务。conversationId={}, round={}",
                    toolBatchTimeoutSeconds,
                    state == null ? null : state.getConversationId(),
                    state == null ? null : state.getRound());
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.warn("批量任务执行异常。conversationId={}, round={}, message={}",
                    state == null ? null : state.getConversationId(),
                    state == null ? null : state.getRound(),
                    e.getMessage());
        }
        return futures.stream()
                .map(f -> {
                    try {
                        return f.getNow(null);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 判断某个任务是否需要进入 HITL 人工确认。
     *
     * @param task 当前任务。
     * @param state 当前执行状态。
     * @return 命中 HITL 拦截规则时返回 true。
     */
    private boolean requiresHitlApproval(PlanTask task, OverallState state) {
        return task != null
                && isHitlEnabled(state)
                && hitlInterceptToolNames.contains(task.toolName());
    }

    /**
     * 在并发控制下执行单个任务，并包装成便于合并的结果对象。
     *
     * @param task 当前任务。
     * @param state 当前执行状态。
     * @return 单任务执行结果。
     */
    private ExecutedTaskMergeResult executeTaskForMerge(PlanTask task, OverallState state) {
        if (task == null || StringUtils.isBlank(task.id())) {
            return null;
        }
        boolean acquired = false;
        try {
            toolSemaphore.acquire();
            acquired = true;
            return new ExecutedTaskMergeResult(task, executeWithRetry(task, state));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutedTaskMergeResult(
                    task,
                    new TaskResult(
                            task.id(),
                            false,
                            false,
                            null,
                            "Task execution interrupted",
                            null
                    )
            );
        } finally {
            if (acquired) {
                toolSemaphore.release();
            }
        }
    }

    /**
     * 将单任务执行结果回写到轮次结果、累计结果和运行时状态中。
     *
     * @param state 当前执行状态。
     * @param results 当前轮结果映射。
     * @param accumulatedResults 跨任务累计结果映射。
     * @param mergeResult 单任务合并结果。
     */
    private void mergeTaskResult(OverallState state,
                                 Map<String, TaskResult> results,
                                 Map<String, String> accumulatedResults,
                                 ExecutedTaskMergeResult mergeResult) {
        if (mergeResult == null || mergeResult.task() == null || mergeResult.result() == null) {
            return;
        }
        PlanTask task = mergeResult.task();
        TaskResult result = mergeResult.result();
        results.put(task.id(), result);
        state.recordTask(task, result);
        logTaskResult(state, task, result);

        if (result.success() && result.output() != null) {
            accumulatedResults.put(task.id(), result.output());
        }
        if (result.interrupted() && result.checkpoint() != null && state.getInterruptedCheckpoint() == null) {
            state.setInterruptedCheckpoint(result.checkpoint());
        }

        appendTaskResultMessage(state, task, result);
    }


    /**
     * 带重试策略执行单个结构化任务。
     *
     * @param task 当前任务。
     * @param state 当前执行状态。
     * @return 最终任务结果；多次失败后返回失败结果。
     */
    private TaskResult executeWithRetry(PlanTask task, OverallState state) {

        int attempt = 0;
        Throwable lastError = null;

        while (attempt < maxToolRetries) {
            attempt++;
            try {
                return executeStructuredToolTask(task, state);
            } catch (Exception e) {
                lastError = e;
                log.warn("任务执行失败，准备重试。conversationId={}, round={}, taskId={}, tool={}, attempt={}, maxRetries={}, message={}",
                        state == null ? null : state.getConversationId(),
                        state == null ? null : state.getRound(),
                        task == null ? null : task.id(),
                        task == null ? null : task.toolName(),
                        attempt,
                        maxToolRetries,
                        e.getMessage(),
                        e);
            }
        }

        return new TaskResult(
                task.id(),
                false,
                false,
                null,
                lastError == null ? "unknown error" : lastError.getMessage(),
                null
        );
    }

    /**
     * 判断当前会话是否启用了 HITL 机制。
     *
     * @param state 当前执行状态。
     * @return HITL 服务可用且当前会话开启拦截时返回 true。
     */
    private boolean isHitlEnabled(OverallState state) {
        return state != null
                && hitlExecutionService != null
                && hitlExecutionService.isEnabled(state.getConversationId(), hitlInterceptToolNames);
    }

    /**
     * 恢复一次被 HITL 中断的执行链，继续执行剩余任务并最终总结。
     *
     * @param interruptId 中断 id。
     * @param userId 当前用户 id。
     * @return 恢复执行后的事件流。
     */
    private Flux<AgentStreamEvent> resumeInternal(String interruptId, Long userId) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        AtomicBoolean interruptedByHitl = new AtomicBoolean(false);
        StringBuilder finalAnswerBuffer = new StringBuilder();

        virtualThreadExecutor.execute(() -> {
            try {
                HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(interruptId);
                ResumeContext resumeContext = PlanExecuteResumeUtils.readContext(checkpoint.getContext());
                OverallState state = restoreResumeState(userId, checkpoint.getConversationId(), resumeContext);
                String runtimeSystemPrompt = resumeContext.runtimeSystemPrompt();
                emitStep(sink, state.getConversationId(), "resume", "Resume", "正在恢复上次被 HITL 中断的执行链...");

                if (isAllFeedbackRejected(checkpoint)) {
                    String rejectSummary = PlanExecutePrompts.buildRejectedToolFinalAnswer();
                    emitStep(sink, state.getConversationId(), "critique", "Critique", "用户已拒绝工具执行，结束当前流程。");
                    emitFinalAnswer(sink, state.getConversationId(), rejectSummary, hasSentFinalResult);
                    addAssistantMemory(state.getConversationId(), rejectSummary);
                    return;
                }

                PlanTask currentTask = resumeContext.currentTask();
                if (currentTask == null) {
                    throw new IllegalStateException("resume 上下文缺少 currentTask");
                }
                PlanTask effectiveCurrentTask = applyEditedArgumentsIfPresent(currentTask, checkpoint);

                Map<String, TaskResult> currentRoundResults = new LinkedHashMap<>();
                TaskResult resumedTaskResult = resumeInterruptedTask(interruptId, effectiveCurrentTask, state);
                currentRoundResults.put(effectiveCurrentTask.id(), resumedTaskResult);
                rememberApprovedToolCallIds(state, checkpoint);
                state.recordTask(effectiveCurrentTask, resumedTaskResult);
                appendTaskResultMessage(state, effectiveCurrentTask, resumedTaskResult);

                if (resumedTaskResult.interrupted() && resumedTaskResult.checkpoint() != null) {
                    state.setInterruptedCheckpoint(resumedTaskResult.checkpoint());
                    enrichInterruptedCheckpoint(state, resumeContext.currentPlan(), currentRoundResults, runtimeSystemPrompt);
                    interruptedByHitl.set(true);
                    sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    return;
                }

                List<PlanTask> remainingPlan = excludeCurrentTask(resumeContext.currentPlan(), effectiveCurrentTask);
                Map<String, TaskResult> remainingResults = executePlan(remainingPlan, state);
                currentRoundResults.putAll(remainingResults);
                emitStep(sink, state.getConversationId(), "task", "Task Result", renderTaskResultsForDisplay(currentRoundResults));

                if (state.getInterruptedCheckpoint() != null) {
                    enrichInterruptedCheckpoint(state, resumeContext.currentPlan(), currentRoundResults, runtimeSystemPrompt);
                    interruptedByHitl.set(true);
                    sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    return;
                }

                LoopAction resumeAction = handleCritique(state);
                if (resumeAction == LoopAction.SUMMARIZE) {
                    log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                            state.getConversationId(), state.getRound());
                    emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
                    finishStreamWithSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
                    return;
                }
                if (resumeAction == LoopAction.ASK_USER) {
                    log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}",
                            state.getConversationId(), state.getRound());
                    emitStep(sink, state.getConversationId(), "critique", "Critique", "需要用户补充信息。");
                    finishStreamWithSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
                    return;
                }
                emitStep(sink, state.getConversationId(), "critique", "Critique Feedback",
                        state.getLastCritique() != null ? state.getLastCritique().feedback() : "");

                while (maxRounds <= 0 || state.getRound() < maxRounds) {
                    state.nextRound();
                    log.info("恢复执行进入第 {} 轮，conversationId={}, interruptId={}",
                            state.getRound(),
                            state.getConversationId(),
                            interruptId);
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    List<PlanTask> plan = generateAndAddPlan(state);
                    log.info("恢复执行阶段已生成本轮计划，conversationId={}, interruptId={}, round={}, taskCount={}, summary={}",
                            state.getConversationId(),
                            interruptId,
                            state.getRound(),
                            plan.size(),
                            summarizePlan(plan));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty()) {
                        log.info("恢复执行阶段未生成可执行任务，直接进入总结。conversationId={}, interruptId={}, round={}",
                                state.getConversationId(),
                                interruptId,
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "execution", "Execution", "当前无需执行工具任务，进入总结阶段。");
                        break;
                    }

                    Map<String, TaskResult> results = executePlan(plan, state);
                    emitStep(sink, state.getConversationId(), "task", "Task Result", renderTaskResultsForDisplay(results));

                    if (state.getInterruptedCheckpoint() != null) {
                        enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                        interruptedByHitl.set(true);
                        sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                        hasSentFinalResult.set(true);
                        sink.tryEmitComplete();
                        return;
                    }

                    LoopAction action = handleCritique(state);
                    if (action == LoopAction.SUMMARIZE) {
                        log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                                state.getConversationId(), state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
                        break;
                    }
                    if (action == LoopAction.ASK_USER) {
                        log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}",
                                state.getConversationId(), state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "需要用户补充信息。");
                        break;
                    }
                    emitStep(sink, state.getConversationId(), "critique", "Critique Feedback",
                            state.getLastCritique() != null ? state.getLastCritique().feedback() : "");
                }

                if (state.getRound() == maxRounds) {
                    log.info("恢复执行达到最大轮次限制，强制进入总结阶段。conversationId={}, interruptId={}, round={}",
                            state.getConversationId(),
                            interruptId,
                            state.getRound());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute", "达到最大轮次，强制进入总结阶段。");
                }

                finishStreamWithSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
            } catch (Exception e) {
                if (!hasSentFinalResult.get()) {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(e);
                }
            }
        });

        return sink.asFlux()
                .doOnCancel(() -> hasSentFinalResult.set(true))
                .doFinally(signalType -> {
                    if (interruptedByHitl.get()) {
                        log.info("恢复执行再次因 HITL 中断，等待用户处理。interruptId={}", interruptId);
                        return;
                    }
                    log.info("恢复执行链已完成，interruptId={}, finalAnswerLength={}",
                            interruptId,
                            finalAnswerBuffer.length());
                });
    }

    /**
     * 判断本次 HITL 反馈是否全部为拒绝。
     *
     * @param checkpoint 当前 checkpoint。
     * @return 所有反馈均为 REJECTED 时返回 true。
     */
    private boolean isAllFeedbackRejected(HitlCheckpointVO checkpoint) {
        if (checkpoint == null || checkpoint.getFeedbacks() == null || checkpoint.getFeedbacks().isEmpty()) {
            return false;
        }
        return checkpoint.getFeedbacks().stream()
                .filter(Objects::nonNull)
                .allMatch(feedback -> feedback.result() == PendingToolCall.FeedbackResult.REJECTED);
    }

    /**
     * 若用户在 HITL 阶段编辑了工具参数，则将编辑结果回填到当前任务。
     *
     * @param task 当前任务。
     * @param checkpoint 当前 checkpoint。
     * @return 应用编辑后参数的任务；无编辑时返回原任务。
     */
    private PlanTask applyEditedArgumentsIfPresent(PlanTask task, HitlCheckpointVO checkpoint) {
        if (task == null || checkpoint == null || checkpoint.getFeedbacks() == null || checkpoint.getFeedbacks().isEmpty()) {
            return task;
        }
        PendingToolCall editedFeedback = checkpoint.getFeedbacks().stream()
                .filter(Objects::nonNull)
                .filter(feedback -> feedback.result() == PendingToolCall.FeedbackResult.EDIT)
                .filter(feedback -> StringUtils.isNotBlank(feedback.arguments()))
                .filter(feedback -> StringUtils.equals(StringUtils.defaultIfBlank(feedback.name(), task.toolName()), task.toolName()))
                .findFirst()
                .orElse(null);
        if (editedFeedback == null) {
            return task;
        }

        try {
            Map<String, Object> parsed = parseJsonMap(editedFeedback.arguments());
            if (parsed == null || parsed.isEmpty()) {
                return task;
            }
            return new PlanTask(
                    task.id(),
                    task.toolName(),
                    parsed,
                    task.order(),
                    task.summary()
            );
        } catch (Exception ex) {
            log.warn("解析 EDIT 后参数失败，回退原任务参数。taskId={}, tool={}, message={}",
                    task.id(),
                    task.toolName(),
                    ex.getMessage());
            return task;
        }
    }

    /**
     * 根据 checkpoint 中保存的上下文重建恢复执行所需的状态对象。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param resumeContext checkpoint 中保存的恢复上下文。
     * @return 重建后的执行状态。
     */
    private OverallState restoreResumeState(Long userId, String conversationId, ResumeContext resumeContext) {
        OverallState state = new OverallState(userId, conversationId, resumeContext.question());
        state.messages.addAll(resumeContext.messages() == null ? List.of() : resumeContext.messages());
        state.executedTasks.putAll(resumeContext.executedTasks() == null ? Map.of() : resumeContext.executedTasks());
        state.approvedToolCallIds.addAll(resumeContext.approvedToolCallIds() == null ? List.of() : resumeContext.approvedToolCallIds());
        state.setSkillRuntimeContexts(resumeContext.skillRuntimeContexts());
        state.round.set(resumeContext.round());
        return state;
    }

    /**
     * 调用 HITL 恢复服务，继续执行当前被中断的工具调用。
     *
     * @param interruptId 中断 id。
     * @param currentTask 当前被中断的任务。
     * @param state 当前执行状态。
     * @return 恢复后的任务结果。
     */
    private TaskResult resumeInterruptedTask(String interruptId, PlanTask currentTask, OverallState state) {
        if (hitlResumeService == null) {
            throw new IllegalStateException("hitlResumeService 未配置，无法 resume");
        }
        HitlResumeResult resumeResult = hitlResumeService.resume(
                new HitlResumeRequest(
                        interruptId,
                        state == null ? null : state.getUserId(),
                        state == null ? null : state.getConversationId(),
                        chatModel,
                        tools,
                        advisors,
                        this.maxRounds,
                        hitlInterceptToolNames,
                        state == null ? Set.of() : state.getApprovedToolCallIds()
                )
        );
        if (resumeResult.interrupted()) {
            return new TaskResult(currentTask == null ? null : currentTask.id(), false, true, null, resumeResult.error(), resumeResult.checkpoint());
        }
        return new TaskResult(currentTask == null ? null : currentTask.id(), true, false, resumeResult.content(), resumeResult.error(), null);
    }


    /**
     * 记录本次 HITL 中被批准继续执行的 tool call id。
     *
     * @param state 当前执行状态。
     * @param checkpoint 当前 checkpoint。
     */
    private void rememberApprovedToolCallIds(OverallState state, HitlCheckpointVO checkpoint) {
        if (state == null || checkpoint == null) {
            return;
        }

        Map<String, PendingToolCall> pendingById = new LinkedHashMap<>();
        for (PendingToolCall pendingToolCall : checkpoint.getPendingToolCalls() == null
                ? List.<PendingToolCall>of()
                : checkpoint.getPendingToolCalls()) {
            if (pendingToolCall != null && StringUtils.isNotBlank(pendingToolCall.id())) {
                pendingById.put(pendingToolCall.id(), pendingToolCall);
            }
        }

        for (PendingToolCall feedback : checkpoint.getFeedbacks() == null ? List.<PendingToolCall>of() : checkpoint.getFeedbacks()) {
            if (feedback == null || StringUtils.isBlank(feedback.id()) || feedback.result() == null) {
                continue;
            }
            if (feedback.result() == PendingToolCall.FeedbackResult.REJECTED) {
                continue;
            }
            PendingToolCall pendingToolCall = pendingById.get(feedback.id());
            if (pendingToolCall == null) {
                continue;
            }
            state.getApprovedToolCallIds().add(pendingToolCall.id());
        }
    }

    /**
     * 发送“开始总结”阶段提示，并进入最终答案流式生成。
     *
     * @param sink 当前事件流 sink。
     * @param state 当前执行状态。
     * @param finalAnswerBuffer 最终答案缓存。
     * @param hasSentFinalResult 是否已发送最终结果标记。
     */
    private void finishStreamWithSummary(Sinks.Many<AgentStreamEvent> sink,
                                         OverallState state,
                                         StringBuilder finalAnswerBuffer,
                                         AtomicBoolean hasSentFinalResult) {
        emitStep(sink, state.getConversationId(), "final", "Final Answer", "正在生成最终答案...");
        streamSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
    }

    /**
     * 处理 `exclude Current Task` 对应逻辑。
     *
     * @param plan plan 参数。
     * @param currentTask currentTask 参数。
     * @return 返回处理结果。
     */
    private List<PlanTask> excludeCurrentTask(List<PlanTask> plan, PlanTask currentTask) {
        List<PlanTask> sanitizedPlan = sanitizePlan(plan, null);
        if (currentTask == null || sanitizedPlan.isEmpty()) {
            return sanitizedPlan;
        }
        return sanitizedPlan.stream()
                .filter(task -> !isSameTask(task, currentTask))
                .toList();
    }

    /**
     * 判断 `is Same Task` 条件是否成立。
     *
     * @param left left 参数。
     * @param right right 参数。
     * @return 返回处理结果。
     */
    private boolean isSameTask(PlanTask left, PlanTask right) {
        if (left == null || right == null) {
            return false;
        }
        return StringUtils.isNotBlank(left.id()) && StringUtils.equals(left.id(), right.id());
    }

    /**
     * 执行 `execute Structured Tool Task` 对应逻辑。
     *
     * @param task task 参数。
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private TaskResult executeStructuredToolTask(PlanTask task, OverallState state) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return new TaskResult(task == null ? null : task.id(), false, false, null, "结构化任务缺少 toolName", null);
        }
        PlanTask effectiveTask = repairStructuredToolTask(task, state);
        ToolCallback callback = findTool(task.toolName());
        if (callback == null) {
            return new TaskResult(effectiveTask.id(), false, false, null, "工具未找到：" + effectiveTask.toolName(), null);
        }
        Map<String, Object> requestArguments = effectiveTask.arguments() == null ? Map.of() : effectiveTask.arguments();
        String validationError = JsonArgumentUtils.validateStructuredToolArguments(effectiveTask.toolName(), requestArguments);
        if (StringUtils.isNotBlank(validationError)) {
            return new TaskResult(effectiveTask.id(), false, false, null, validationError, null);
        }
        String argsJson = toJsonStr(requestArguments);
        log.info("开始执行结构化任务，conversationId={}, round={}, taskId={}, tool={}, argumentKeys={}",
                state == null ? null : state.getConversationId(),
                state == null ? null : state.getRound(),
                effectiveTask.id(),
                effectiveTask.toolName(),
                requestArguments.keySet());

        try {
            if (isHitlEnabled(state)
                    && hitlInterceptToolNames.contains(effectiveTask.toolName())) {
                HitlCheckpointVO checkpoint = createDirectToolCheckpoint(
                        state,
                        effectiveTask.toolName(),
                        argsJson,
                        "该工具需要用户手动确认。已锁定结构化 toolName 和 arguments。"
                );
                log.info("结构化任务命中 HITL 中断，conversationId={}, round={}, taskId={}, tool={}, interruptId={}",
                        state == null ? null : state.getConversationId(),
                        state == null ? null : state.getRound(),
                        effectiveTask.id(),
                        effectiveTask.toolName(),
                        checkpoint.getInterruptId());
                return new TaskResult(effectiveTask.id(), false, true, null, "Task execution interrupted by HITL", checkpoint);
            }

            String result;
            try (AgentRuntimeContext.Scope ignored = AgentRuntimeContext.open(
                    state == null ? null : state.getUserId(),
                    state == null ? null : state.getConversationId())) {
                result = String.valueOf(callback.call(argsJson));
            }
            return new TaskResult(effectiveTask.id(), true, false, result, null, null);
        } catch (Exception e) {
            return new TaskResult(effectiveTask.id(), false, false, null, e.getMessage(), null);
        }
    }

    /**
     * 处理 `repair Structured Tool Task` 对应逻辑。
     *
     * @param task task 参数。
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private PlanTask repairStructuredToolTask(PlanTask task, OverallState state) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return task;
        }
        Map<String, Object> templateArguments = resolveStructuredToolTemplate(task, state);
        Map<String, Object> repairedArguments = JsonArgumentUtils.repairStructuredToolArguments(
                task.toolName(),
                task.arguments(),
                templateArguments
        );
        if (Objects.equals(repairedArguments, task.arguments())) {
            return task;
        }
        return new PlanTask(task.id(), task.toolName(), repairedArguments, task.order(), task.summary());
    }

    private Map<String, Object> resolveStructuredToolTemplate(PlanTask task, OverallState state) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return Map.of();
        }
        if (!JsonArgumentUtils.EXECUTE_SKILL_SCRIPT_TOOL.equals(task.toolName())) {
            return Map.of();
        }

        SkillRuntimeContext runtimeSkillContext = findMatchingRuntimeSkillContext(task, state);
        if (runtimeSkillContext != null) {
            return toExecuteSkillTemplate(runtimeSkillContext);
        }
        return Map.of();
    }

    private SkillRuntimeContext findMatchingRuntimeSkillContext(PlanTask task, OverallState state) {
        List<SkillRuntimeContext> skillRuntimeContexts = state == null ? List.of() : state.getSkillRuntimeContexts();
        if (skillRuntimeContexts.isEmpty()) {
            return null;
        }
        List<SkillRuntimeContext> matchedContexts = skillRuntimeContexts.stream()
                .filter(Objects::nonNull)
                .toList();
        if (matchedContexts.isEmpty()) {
            return null;
        }
        if (matchedContexts.size() == 1) {
            return matchedContexts.getFirst();
        }
        Map<String, Object> rawArguments = task.arguments() == null ? Map.of() : task.arguments();
        Object nestedArguments = rawArguments.get("arguments");
        String currentSkillName = stringValue(rawArguments.get("skillName"));
        if (StringUtils.isBlank(currentSkillName) && nestedArguments instanceof Map<?, ?> nestedMap) {
            currentSkillName = stringValue(nestedMap.get("skillName"));
        }
        if (StringUtils.isBlank(currentSkillName)) {
            return null;
        }
        String finalCurrentSkillName = currentSkillName;
        return matchedContexts.stream()
                .filter(context -> StringUtils.equals(finalCurrentSkillName, context.getSkillName()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> toExecuteSkillTemplate(SkillRuntimeContext context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> template = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(context.getSkillName())) {
            template.put("skillName", context.getSkillName());
        }
        if (Boolean.TRUE.equals(context.getHasExecutableScript())) {
            if (StringUtils.isNotBlank(context.getSingleScriptPath())) {
                template.put("scriptPath", context.getSingleScriptPath());
            }
            Map<String, Object> defaultArguments = new LinkedHashMap<>();
            if (context.getScriptArgumentSpecs() != null && !context.getScriptArgumentSpecs().isEmpty()) {
                template.put("argumentSpecs", context.getScriptArgumentSpecs());
                for (Map.Entry<String, SkillArgumentSpec> entry : context.getScriptArgumentSpecs().entrySet()) {
                    SkillArgumentSpec spec = entry.getValue();
                    if (spec != null) {
                        defaultArguments.put(entry.getKey(), spec.getDefaultValue());
                    }
                }
            }
            template.put("arguments", defaultArguments);
        } else {
            if (StringUtils.isNotBlank(context.getContent())) {
                template.put("skillContent", context.getContent());
            }
        }
        return template;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 创建 `create Direct Tool Checkpoint` 对应内容。
     *
     * @param state state 参数。
     * @param toolName toolName 参数。
     * @param argumentsJson argumentsJson 参数。
     * @param description description 参数。
     * @return 返回处理结果。
     */
    private HitlCheckpointVO createDirectToolCheckpoint(OverallState state,
                                                        String toolName,
                                                        String argumentsJson,
                                                        String description) {
        if (state == null || hitlCheckpointService == null) {
            throw new IllegalStateException("hitlCheckpointService 未配置，无法创建直接工具确认 checkpoint");
        }
        String toolCallId = TOOL_CALL_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String enrichedArgumentsJson = enrichHitlArgumentsJson(toolName, argumentsJson);
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                toolCallId,
                TOOL_CALL_TYPE_FUNCTION,
                toolName,
                enrichedArgumentsJson
        );

        List<Message> checkpointMessages = new ArrayList<>(state.getMessages());
        checkpointMessages.add(AssistantMessage.builder().toolCalls(List.of(toolCall)).build());

        PendingToolCall pendingToolCall = new PendingToolCall(
                toolCallId,
                toolName,
                enrichedArgumentsJson,
                null,
                description
        );
        return hitlCheckpointService.createCheckpoint(
                state.getConversationId(),
                agentType,
                List.of(pendingToolCall),
                checkpointMessages,
                Map.of()
        );
    }

    /**
     * 处理 `enrich Hitl Arguments Json` 对应逻辑。
     *
     * @param toolName toolName 参数。
     * @param argumentsJson argumentsJson 参数。
     * @return 返回处理结果。
     */
    private String enrichHitlArgumentsJson(String toolName, String argumentsJson) {
        if (!JsonArgumentUtils.EXECUTE_SKILL_SCRIPT_TOOL.equals(toolName) || StringUtils.isBlank(argumentsJson) || skillService == null) {
            return argumentsJson;
        }
        try {
            JsonNode jsonNode = PLAN_OBJECT_MAPPER.readTree(argumentsJson);
            if (jsonNode.has("argumentSpecs")) {
                return argumentsJson;
            }
            String skillName = jsonNode.has("skillName") ? jsonNode.get("skillName").asText() : null;
            String scriptPath = jsonNode.has("scriptPath") ? jsonNode.get("scriptPath").asText() : null;
            if (StringUtils.isAnyBlank(skillName, scriptPath)) {
                return argumentsJson;
            }
            Map<String, SkillArgumentSpec> argumentSpecs = skillService.resolveSkillArgumentSpecs(skillName, scriptPath);
            if (argumentSpecs == null || argumentSpecs.isEmpty()) {
                return argumentsJson;
            }
            ((ObjectNode) jsonNode).set("argumentSpecs", PLAN_OBJECT_MAPPER.valueToTree(argumentSpecs));
            return PLAN_OBJECT_MAPPER.writeValueAsString(jsonNode);
        } catch (Exception ex) {
            log.warn("补充 HITL argumentSpecs 失败，toolName={}, message={}", toolName, ex.getMessage());
            return argumentsJson;
        }
    }

    private void enrichInterruptedCheckpoint(OverallState state,
                                             List<PlanTask> plan,
                                             Map<String, TaskResult> results,
                                             String runtimeSystemPrompt) {
        if (state == null || state.getInterruptedCheckpoint() == null || hitlCheckpointService == null) {
            return;
        }
        PlanTask interruptedTask = findInterruptedTask(plan, results);
        Map<String, Object> additionalContext = PlanExecuteResumeUtils.buildContext(
                state,
                plan,
                interruptedTask,
                runtimeSystemPrompt
        );
        HitlCheckpointVO updatedCheckpoint = hitlCheckpointService.appendContext(
                state.getInterruptedCheckpoint().getInterruptId(),
                additionalContext
        );
        state.setInterruptedCheckpoint(updatedCheckpoint);
    }

    /**
     * 查找 `find Interrupted Task` 对应结果。
     *
     * @param plan plan 参数。
     * @param results results 参数。
     * @return 返回处理结果。
     */
    private PlanTask findInterruptedTask(List<PlanTask> plan, Map<String, TaskResult> results) {
        if (plan == null || plan.isEmpty() || results == null || results.isEmpty()) {
            return null;
        }
        Set<String> interruptedTaskIds = results.values().stream()
                .filter(TaskResult::interrupted)
                .map(TaskResult::taskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return plan.stream()
                .filter(Objects::nonNull)
                .filter(task -> interruptedTaskIds.contains(task.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 处理 `append Task Result Message` 对应逻辑。
     *
     * @param state state 参数。
     * @param task task 参数。
     * @param result result 参数。
     */
    private void appendTaskResultMessage(OverallState state, PlanTask task, TaskResult result) {
        if (state == null || task == null || result == null) {
            return;
        }
        String argumentsJson = task.arguments() == null ? "{}" : toJsonStr(task.arguments());
        state.add(new AssistantMessage(String.format(
                "【Completed Task Result】%n" +
                        "taskId: %s%n" +
                        "toolName: %s%n" +
                        "summary: %s%n" +
                        "arguments:%n%s%n" +
                        "success: %s%n" +
                        "interrupted: %s%n" +
                        "result:%n%s%n" +
                        "error:%n%s%n" +
                        "【End Task Result】",
                task.id(),
                defaultTaskValue(task.toolName()),
                defaultTaskValue(task.summary()),
                argumentsJson,
                result.success(),
                result.interrupted(),
                StringUtils.defaultString(result.output()),
                StringUtils.defaultString(result.error())
        )));
    }

    /**
     * 构建 `build Hitl Interrupt Event` 对应结果。
     *
     * @param conversationId conversationId 参数。
     * @param checkpoint checkpoint 参数。
     * @return 返回处理结果。
     */
    private AgentStreamEvent buildHitlInterruptEvent(String conversationId, HitlCheckpointVO checkpoint) {
        if (checkpoint == null) {
            return AgentStreamEventFactory.emptyHitlInterrupt(conversationId);
        }
        return AgentStreamEventFactory.hitlInterrupt(
                conversationId,
                checkpoint.getInterruptId(),
                checkpoint.getAgentType(),
                checkpoint.getStatus(),
                checkpoint.getPendingToolCalls()
        );
    }

    /**
     * 处理 `render Dependency Snapshot` 对应逻辑。
     *
     * @param results results 参数。
     * @return 返回处理结果。
     */
    private String renderDependencySnapshot(Map<String, String> results) {

        if (results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        results.forEach((taskId, output) -> {
            sb.append("- taskId: ")
                    .append(taskId)
                    .append("\n")
                    .append("  output:\n")
                    .append(output)
                    .append("\n\n");
        });

        return sb.toString();
    }

    /**
     * 处理 `render Executed Task History` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private String renderExecutedTaskHistory(OverallState state) {
        if (state == null || state.getExecutedTasks().isEmpty()) {
            return "暂无已执行任务。";
        }

        return state.getExecutedTasks().values().stream()
                .sorted(Comparator.comparingInt(ExecutedTaskSnapshot::round))
                .map(snapshot -> "- round: %s, success: %s, toolName: %s, summary: %s, arguments: %s".formatted(
                        snapshot.round(),
                        snapshot.success(),
                        defaultTaskValue(snapshot.toolName()),
                        defaultTaskValue(snapshot.summary()),
                        snapshot.arguments() == null ? "{}" : toJsonStr(snapshot.arguments())))
                .collect(Collectors.joining("\n"));
    }

    private CritiqueResult critique(OverallState state) {
        BeanOutputConverter<CritiqueResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PlanExecutePrompts.getCritiquePrompt()),
                new UserMessage(renderMessages(state.getMessages()))
        ));
        String raw = chatModel.call(prompt).getResult().getOutput().getText();

        try {
            return converter.convert(raw);
        } catch (Exception ex) {
            log.warn("critique 解析失败，尝试修复 JSON。conversationId={}, round={}",
                    state.getConversationId(), state.getRound());
            try {
                String repaired = JsonUtil.fixJson(raw);
                return converter.convert(repaired);
            } catch (Exception repairEx) {
                log.warn("critique JSON 修复后仍解析失败，降级为总结。conversationId={}, round={}",
                        state.getConversationId(), state.getRound());
                return new CritiqueResult(true, CritiqueResult.ACTION_SUMMARIZE, "JSON 修复失败，直接进入总结");
            }
        }
    }

    /**
     * 处理 `compress If Needed` 对应逻辑。
     *
     * @param state state 参数。
     */
    private void compressIfNeeded(OverallState state) {

        if (state.currentChars() < contextCharLimit) {
            return;
        }

        log.warn("上下文过大，开始压缩。conversationId={}, round={}, size={}",
                state.getConversationId(),
                state.getRound(),
                state.currentChars());

        String compressLimitPrompt = PlanExecutePrompts.formatCompressPrompt(contextCharLimit);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(compressLimitPrompt),

                new UserMessage(renderMessages(state.getMessages()))
        ));

        String snapshot = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getText();

        state.clearMessages();
        state.add(new SystemMessage(PlanExecutePrompts.buildCompressedStateMessage(snapshot)));
        log.warn("上下文压缩完成。conversationId={}, round={}, size={}",
                state.getConversationId(),
                state.getRound(),
                state.currentChars());
    }


    /**
     * 流式处理 `stream Summary` 对应内容。
     *
     * @param sink sink 参数。
     * @param state state 参数。
     * @param finalAnswerBuffer finalAnswerBuffer 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     */
    private void streamSummary(Sinks.Many<AgentStreamEvent> sink,
                               OverallState state,
                               StringBuilder finalAnswerBuffer,
                               AtomicBoolean hasSentFinalResult) {
        Prompt prompt = buildSummarizePrompt(state);
        String conversationId = state == null ? null : state.getConversationId();

        chatClient.prompt(prompt)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .doOnNext(chunk -> processSummaryChunk(chunk, sink, finalAnswerBuffer, conversationId, hasSentFinalResult))
                .doOnComplete(() -> completeSummaryStream(sink, state, finalAnswerBuffer, hasSentFinalResult))
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
    }

    /**
     * 处理 `summarize` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private String summarize(OverallState state) {
        Prompt prompt = buildSummarizePrompt(state);
        String answer = chatModel.call(prompt).getResult().getOutput().getText();
        addAssistantMemory(state.conversationId, answer);
        return answer;
    }

    /**
     * 处理 `process Summary Chunk` 对应逻辑。
     *
     * @param chunk chunk 参数。
     * @param sink sink 参数。
     * @param finalAnswerBuffer finalAnswerBuffer 参数。
     * @param conversationId conversationId 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     */
    private void processSummaryChunk(ChatResponse chunk,
                                     Sinks.Many<AgentStreamEvent> sink,
                                     StringBuilder finalAnswerBuffer,
                                     String conversationId,
                                     AtomicBoolean hasSentFinalResult) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null || hasSentFinalResult.get()) {
            return;
        }
        Generation generation = chunk.getResult();
        String text = generation.getOutput().getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        finalAnswerBuffer.append(text);
        sink.tryEmitNext(AgentStreamEventFactory.assistantDelta(conversationId, text));
    }

    /**
     * 处理 `complete Summary Stream` 对应逻辑。
     *
     * @param sink sink 参数。
     * @param state state 参数。
     * @param finalAnswerBuffer finalAnswerBuffer 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     */
    private void completeSummaryStream(Sinks.Many<AgentStreamEvent> sink,
                                       OverallState state,
                                       StringBuilder finalAnswerBuffer,
                                       AtomicBoolean hasSentFinalResult) {
        if (hasSentFinalResult.get()) {
            return;
        }
        String answer = finalAnswerBuffer.toString();
        hasSentFinalResult.set(true);
        sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(state.getConversationId(), answer));
        sink.tryEmitComplete();
        virtualThreadExecutor.execute(() -> addAssistantMemory(state.conversationId, answer));
    }

    /**
     * 处理 `emit Final Answer` 对应逻辑。
     *
     * @param sink sink 参数。
     * @param conversationId conversationId 参数。
     * @param content content 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     */
    private void emitFinalAnswer(Sinks.Many<AgentStreamEvent> sink,
                                 String conversationId,
                                 String content,
                                 AtomicBoolean hasSentFinalResult) {
        if (hasSentFinalResult.get()) {
            return;
        }
        sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(conversationId, content == null ? "" : content));
        hasSentFinalResult.set(true);
        sink.tryEmitComplete();
    }

    /**
     * 处理 `log Task Result` 对应逻辑。
     *
     * @param state state 参数。
     * @param task task 参数。
     * @param result result 参数。
     */
    private void logTaskResult(OverallState state, PlanTask task, TaskResult result) {
        if (task == null || result == null) {
            return;
        }
        log.info("结构化任务执行完成，conversationId={}, round={}, taskId={}, tool={}, success={}, interrupted={}, outputLength={}, error={}",
                state == null ? null : state.getConversationId(),
                state == null ? null : state.getRound(),
                task.id(),
                task.toolName(),
                result.success(),
                result.interrupted(),
                result.output() == null ? 0 : result.output().length(),
                abbreviate(result.error(), 200));
    }

    /**
     * 处理 `summarize Plan` 对应逻辑。
     *
     * @param plan plan 参数。
     * @return 返回处理结果。
     */
    private String summarizePlan(List<PlanTask> plan) {
        if (plan == null || plan.isEmpty()) {
            return "[]";
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .map(task -> "{id=%s,order=%s,tool=%s}".formatted(
                        defaultTaskValue(task.id()),
                        task.order(),
                        defaultTaskValue(task.toolName())))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 处理 `abbreviate` 对应逻辑。
     *
     * @param text text 参数。
     * @param maxLength maxLength 参数。
     * @return 返回处理结果。
     */
    private String abbreviate(String text, int maxLength) {
        if (text == null || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 构建 `build Summarize Prompt` 对应结果。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private Prompt buildSummarizePrompt(OverallState state) {
        String systemMessageContent = PlanExecutePrompts.buildSummarySystemPrompt();

        String userMessageContent = PlanExecutePrompts.buildSummaryUserPrompt(
                state.getQuestion(),
                renderLatestConfirmedArguments(state),
                renderMessages(state.getMessages())
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessageContent),
                new UserMessage(userMessageContent)
        ));
        return prompt;
    }

    /**
     * 处理 `render Messages` 对应逻辑。
     *
     * @param messages messages 参数。
     * @return 返回处理结果。
     */
    private String renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n")
                    .append(m.getText());
        }
        return sb.toString();
    }

    /**
     * 处理 `default Task Value` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    private String defaultTaskValue(String value) {
        return StringUtils.defaultIfBlank(value, "-");
    }

    /**
     * 处理 `render Latest Confirmed Arguments` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private String renderLatestConfirmedArguments(OverallState state) {
        if (state == null || state.getExecutedTasks().isEmpty()) {
            return "无";
        }
        return state.getExecutedTasks().values().stream()
                .sorted(Comparator.comparingInt(ExecutedTaskSnapshot::round).reversed())
                .map(ExecutedTaskSnapshot::arguments)
                .filter(Objects::nonNull)
                .filter(arguments -> !arguments.isEmpty())
                .findFirst()
                .map(PlanExecuteAgent::toJsonStr)
                .orElse("无");
    }

    /**
     * 创建 `ExecutedTaskMergeResult` 实例。
     *
     * @param task task 参数。
     * @param result result 参数。
     */
    /**
     * `ExecutedTaskMergeResult` 记录对象。
     */
    private record ExecutedTaskMergeResult(PlanTask task, TaskResult result) {
    }

    /**
     * `OverallState` 类型实现。
     */
    @Getter
    public static class OverallState {

        private final Long userId;
        private final String conversationId;
        private final String question;
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private final Map<String, ExecutedTaskSnapshot> executedTasks = new ConcurrentHashMap<>();
        private final Set<String> approvedToolCallIds = ConcurrentHashMap.newKeySet();
        private volatile List<SkillRuntimeContext> skillRuntimeContexts = List.of();
        private volatile HitlCheckpointVO interruptedCheckpoint;
        private volatile CritiqueResult lastCritique;
        private final AtomicInteger round = new AtomicInteger(0);

        /**
         * 创建 `OverallState` 实例。
         *
         * @param userId userId 参数。
         * @param conversationId conversationId 参数。
         * @param question question 参数。
         */
        public OverallState(Long userId, String conversationId, String question) {
            this.userId = userId;
            this.question = question;
            this.conversationId = conversationId;
        }

        public void nextRound() {
            round.incrementAndGet();
        }

        public int getRound() {
            return round.get();
        }

        public void add(Message m) {
            messages.add(m);
        }

        public int currentChars() {
            return messages.stream()
                    .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                    .sum();
        }

        public void clearMessages() {
            messages.clear();
        }

        public Set<String> getApprovedToolCallIds() {
            return approvedToolCallIds;
        }

        public void setInterruptedCheckpoint(HitlCheckpointVO interruptedCheckpoint) {
            this.interruptedCheckpoint = interruptedCheckpoint;
        }

        public void setSkillRuntimeContexts(List<SkillRuntimeContext> skillRuntimeContexts) {
            this.skillRuntimeContexts = skillRuntimeContexts == null ? List.of() : skillRuntimeContexts;
        }

        public void recordTask(PlanTask task, TaskResult result) {
            if (task == null || result == null) {
                return;
            }
            String executionKey = (task.id() == null ? "task" : task.id()) + "#" + executedTasks.size();
            executedTasks.put(executionKey, new ExecutedTaskSnapshot(
                    task.id(),
                    task.toolName(),
                    task.arguments(),
                    task.summary(),
                    result.success(),
                    result.output(),
                    result.error(),
                    round.get()
            ));
        }
    }
}
