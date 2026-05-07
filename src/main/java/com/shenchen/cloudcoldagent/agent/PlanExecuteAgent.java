package com.shenchen.cloudcoldagent.agent;

import cn.hutool.json.JSONUtil;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.CritiqueResult;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ExecutedTaskSnapshot;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ResumeContext;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.TaskResult;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.PlanExecutePrompts;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.utils.PlanExecuteResumeUtils;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
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
import java.util.concurrent.Semaphore;
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

    private final ChatClient chatClient;

    private volatile List<SkillRuntimeContext> runtimeSkillContexts = List.of();

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
                            int toolConcurrency,
                            Set<String> hitlInterceptToolNames) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.contextCharLimit = contextCharLimit;
        this.maxToolRetries = maxToolRetries;
        this.toolSemaphore = new Semaphore(Math.max(1, toolConcurrency));
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.skillService = skillService;
        this.agentType = StringUtils.isBlank(agentType) ? "PlanExecuteAgent" : agentType;
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

    public void setRuntimeSkillContexts(List<SkillRuntimeContext> skillContexts) {
        this.runtimeSkillContexts = skillContexts == null ? List.of() : List.copyOf(skillContexts);
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

        private int toolConcurrency = 3;

        private Set<String> hitlInterceptToolNames = new LinkedHashSet<>();

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
            this.tools = Arrays.asList(tools);
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
            return new PlanExecuteAgent(chatModel, tools, advisors, maxRounds, contextCharLimit, maxToolRetries,
                    chatMemory, hitlExecutionService, hitlCheckpointService, hitlResumeService, skillService,
                    agentType, toolConcurrency, hitlInterceptToolNames);
        }
    }

    /**
     * 以无显式用户上下文的方式同步调用 plan-execute Agent。
     *
     * @param question 用户问题。
     * @return 最终回答。
     */
    public String call(String question) {
        return callInternal(null, null, question, null, question);
    }

    /**
     * 以带会话上下文的方式同步调用 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @return 最终回答。
     */
    public String call(Long userId, String conversationId, String question) {
        return callInternal(userId, conversationId, question, null, question);
    }

    /**
     * 以带运行时 system prompt 的方式同步调用 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @return 最终回答。
     */
    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    /**
     * 以无显式用户上下文的方式流式执行 plan-execute Agent。
     *
     * @param question 用户问题。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return streamInternal(null, null, question, null, question);
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
        return streamInternal(userId, conversationId, question, null, question);
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
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    /**
     * 以完整参数形式流式执行 plan-execute Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return 面向前端的 Agent 事件流。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
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
                                                 String memoryQuestion) {
        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(userId, conversationId, question);
        state.setSkillRuntimeContexts(this.runtimeSkillContexts);

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

        Schedulers.boundedElastic().schedule(() -> {
            try {
                while (maxRounds <= 0 || state.getRound() < maxRounds) {
                    state.nextRound();
                    log.info("开始执行 Plan-Execute 第 {} 轮，conversationId={}",
                            state.getRound(),
                            state.getConversationId());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    // 1.生成计划
                    List<PlanTask> plan = sanitizePlan(generatePlan(state), state);
                    String planText = "【Execution Plan】\n" + plan;
                    log.info("本轮执行计划已生成，conversationId={}, round={}, taskCount={}, summary={}",
                            state.getConversationId(),
                            state.getRound(),
                            plan.size(),
                            summarizePlan(plan));
                    state.add(new AssistantMessage(planText));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                        log.info("本轮未生成可执行任务，直接进入总结阶段。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "execution", "Execution", "当前无需执行工具任务，进入总结阶段。");
                        break;
                    }

                    // 2.执行
                    Map<String, TaskResult> results = executePlan(plan, state, runtimeSystemPrompt);
                    emitStep(sink, state.getConversationId(), "task", "Task Result", renderTaskResultsForDisplay(results));

                    if (state.getInterruptedCheckpoint() != null) {
                        enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                        interruptedByHitl.set(true);
                        sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                        hasSentFinalResult.set(true);
                        sink.tryEmitComplete();
                        return;
                    }

                    // 3.批判
                    CritiqueResult critique = critique(state);
                    String critiqueText = "【Critique Feedback】\n" + critique.feedback();

                    if (critique.passed() || CritiqueResult.ACTION_SUMMARIZE.equals(critique.action())) {
                        log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
                        break;
                    }

                    if (CritiqueResult.ACTION_ASK_USER.equals(critique.action())) {
                        log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}, feedback={}",
                                state.getConversationId(),
                                state.getRound(),
                                abbreviate(critique.feedback(), 200));
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "需要用户补充信息。");
                        state.add(new AssistantMessage(critiqueText));
                        break;
                    }

                    if (shouldStopAfterSkillResourceExhausted(state)) {
                        log.info("批判阶段判断 skill 资源已穷尽，停止继续规划。conversationId={}, round={}",
                                state.getConversationId(),
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "已确认 skill 可读资源已穷尽，停止无效重试并直接总结。");
                        break;
                    }

                    log.info("批判阶段要求继续下一轮规划。conversationId={}, round={}, feedback={}",
                            state.getConversationId(),
                            state.getRound(),
                            abbreviate(critique.feedback(), 200));
                    state.add(new AssistantMessage(critiqueText));
                    emitStep(sink, state.getConversationId(), "critique", "Critique Feedback", critique.feedback());

                    // 4. 压缩context
                    compressIfNeeded(state);
                }
                if (state.round == maxRounds) {
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
                        task.arguments() == null ? "{}" : JSONUtil.toJsonStr(task.arguments())))
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
     * @return 最终回答，若命中 HITL 中断则返回中断事件 JSON。
     */
    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    /**
     * plan-execute 的同步执行主循环，负责规划、批判、上下文压缩和最终总结。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return 最终回答文本，或中断事件 JSON。
     */
    public String callInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {

        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(userId, conversationId, question);
        state.setSkillRuntimeContexts(this.runtimeSkillContexts);

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

        // 【新增】用于判断是否已经执行过工具
        boolean hasExecutedTools = false;

        while (maxRounds <= 0 || state.getRound() < maxRounds) {
            state.nextRound();
            log.info("开始执行 Plan-Execute 第 {} 轮，conversationId={}",
                    state.getRound(),
                    state.getConversationId());

            List<PlanTask> plan = sanitizePlan(generatePlan(state), state);
            log.info("本轮执行计划已生成，conversationId={}, round={}, taskCount={}, summary={}",
                    state.getConversationId(),
                    state.getRound(),
                    plan.size(),
                    summarizePlan(plan));
            state.add(new AssistantMessage("【Execution Plan】\n" + plan));

            if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                log.info("本轮未生成可执行任务，直接进入总结阶段。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }

            // 2.执行
            Map<String, TaskResult> results = executePlan(plan, state, runtimeSystemPrompt);
            hasExecutedTools = (results != null && !results.isEmpty());
            if (state.getInterruptedCheckpoint() != null) {
                enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                return JSONUtil.toJsonStr(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
            }

            // 3.批判
            CritiqueResult critique = critique(state);

            if (critique.passed() || CritiqueResult.ACTION_SUMMARIZE.equals(critique.action())) {
                log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }

            if (CritiqueResult.ACTION_ASK_USER.equals(critique.action())) {
                log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}, feedback={}",
                        state.getConversationId(),
                        state.getRound(),
                        abbreviate(critique.feedback(), 200));
                state.add(new AssistantMessage(String.format("【Critique Feedback】%n%s", critique.feedback())));
                break;
            }

            if (shouldStopAfterSkillResourceExhausted(state)) {
                log.info("批判阶段判断 skill 资源已穷尽，停止继续规划。conversationId={}, round={}",
                        state.getConversationId(),
                        state.getRound());
                break;
            }

            log.info("批判阶段要求继续下一轮规划。conversationId={}, round={}, feedback={}",
                    state.getConversationId(),
                    state.getRound(),
                    abbreviate(critique.feedback(), 200));
            state.add(new AssistantMessage(String.format("【Critique Feedback】%n%s", critique.feedback())));

            // 4. 压缩context
            compressIfNeeded(state);
        }
        if (state.round == maxRounds)
            log.info("Plan-Execute 达到最大轮次限制，强制进入总结阶段。conversationId={}, round={}",
                    state.getConversationId(),
                    state.getRound());

        // 5.总结输出
        return summarize(state);
    }

    /**
     * 调用模型为当前轮生成结构化执行计划。
     *
     * @param state 当前执行状态。
     * @return 结构化任务列表；解析失败时返回空列表。
     */
    private List<PlanTask> generatePlan(OverAllState state) {
        String toolDesc = renderToolDescriptions();
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        String planPrompt = PlanExecutePrompts.formatPlanPrompt(
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                state.round,
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
            log.warn("执行计划解析失败，降级为空计划。conversationId={}, round={}, rawSnippet={}",
                    state.getConversationId(),
                    state.getRound(),
                    abbreviate(raw, 400),
                    ex);
            return List.of();
        }
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
    private List<PlanTask> sanitizePlan(List<PlanTask> plan, OverAllState state) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .filter(task -> task.id() == null || StringUtils.isNotBlank(task.toolName()))
                .map(task -> repairStructuredToolTask(task, state))
                .toList();
    }

    /**
     * 汇总当前 Agent 可用工具描述，供规划阶段注入到提示词中。
     *
     * @return 工具名称和说明拼接成的文本。
     */
    private String renderToolDescriptions() {
        if (tools == null || tools.isEmpty()) {
            return "（当前无可用工具）";
        }

        StringBuilder sb = new StringBuilder();
        for (ToolCallback tool : tools) {
            sb.append("- ")
                    .append(tool.getToolDefinition().name())
                    .append(": ")
                    .append(tool.getToolDefinition().description())
                    .append("\n");
        }
        return sb.toString();
    }


    /**
     * 执行一轮计划中的全部任务，按 order 串行、同 order 内按配置并发。
     *
     * @param plan 当前轮计划任务。
     * @param state 当前执行状态。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @return 任务 id 到执行结果的映射。
     */
    private Map<String, TaskResult> executePlan(List<PlanTask> plan, OverAllState state, String runtimeSystemPrompt) {

        Map<String, TaskResult> results = new LinkedHashMap<>();

        // 按 order 分组：order 相同的 task 可并行
        Map<Integer, List<PlanTask>> grouped =
                plan.stream().collect(Collectors.groupingBy(PlanTask::order));

        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();

        // 按 order 顺序执行（不同 order 串行）
        for (Integer order : new TreeSet<>(grouped.keySet())) {

            // 保存当前工具执行快照
            String dependencySnapshot = renderDependencySnapshot(accumulatedResults);

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

            for (ExecutedTaskMergeResult mergeResult : executeTaskBatch(nonHitlTasks, dependencySnapshot, runtimeSystemPrompt, state)) {
                mergeTaskResult(state, results, accumulatedResults, mergeResult);
            }
            if (state.getInterruptedCheckpoint() != null) {
                break;
            }

            for (PlanTask hitlTask : hitlTasks) {
                ExecutedTaskMergeResult mergeResult = executeTaskForMerge(hitlTask, dependencySnapshot, runtimeSystemPrompt, state);
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
     * @param dependencySnapshot 前置任务结果快照。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param state 当前执行状态。
     * @return 批量任务的合并结果列表。
     */
    private List<ExecutedTaskMergeResult> executeTaskBatch(List<PlanTask> tasks,
                                                           String dependencySnapshot,
                                                           String runtimeSystemPrompt,
                                                           OverAllState state) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<ExecutedTaskMergeResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> executeTaskForMerge(task, dependencySnapshot, runtimeSystemPrompt, state)
                ))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 判断某个任务是否需要进入 HITL 人工确认。
     *
     * @param task 当前任务。
     * @param state 当前执行状态。
     * @return 命中 HITL 拦截规则时返回 true。
     */
    private boolean requiresHitlApproval(PlanTask task, OverAllState state) {
        return task != null
                && isHitlEnabled(state)
                && hitlInterceptToolNames.contains(task.toolName());
    }

    /**
     * 在并发控制下执行单个任务，并包装成便于合并的结果对象。
     *
     * @param task 当前任务。
     * @param dependencySnapshot 前置任务结果快照。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param state 当前执行状态。
     * @return 单任务执行结果。
     */
    private ExecutedTaskMergeResult executeTaskForMerge(PlanTask task,
                                                        String dependencySnapshot,
                                                        String runtimeSystemPrompt,
                                                        OverAllState state) {
        if (task == null || StringUtils.isBlank(task.id())) {
            return null;
        }
        boolean acquired = false;
        try {
            toolSemaphore.acquire();
            acquired = true;
            return new ExecutedTaskMergeResult(task, executeWithRetry(task, dependencySnapshot, runtimeSystemPrompt, state));
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
    private void mergeTaskResult(OverAllState state,
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
     * @param dependencySnapshot 前置任务结果快照。
     * @param runtimeSystemPrompt 运行时 system prompt。
     * @param state 当前执行状态。
     * @return 最终任务结果；多次失败后返回失败结果。
     */
    private TaskResult executeWithRetry(PlanTask task, String dependencySnapshot, String runtimeSystemPrompt, OverAllState state) {

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
    private boolean isHitlEnabled(OverAllState state) {
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

        Schedulers.boundedElastic().schedule(() -> {
            try {
                HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(interruptId);
                ResumeContext resumeContext = PlanExecuteResumeUtils.readContext(checkpoint.getContext());
                OverAllState state = restoreResumeState(userId, checkpoint.getConversationId(), resumeContext);
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
                Map<String, TaskResult> remainingResults = executePlan(remainingPlan, state, runtimeSystemPrompt);
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

                if (!shouldContinueAfterCritique(sink, state, currentRoundResults)) {
                    finishStreamWithSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
                    return;
                }

                while (maxRounds <= 0 || state.getRound() < maxRounds) {
                    state.nextRound();
                    log.info("恢复执行进入第 {} 轮，conversationId={}, interruptId={}",
                            state.getRound(),
                            state.getConversationId(),
                            interruptId);
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    List<PlanTask> plan = sanitizePlan(generatePlan(state), state);
                    String planText = "【Execution Plan】\n" + plan;
                    log.info("恢复执行阶段已生成本轮计划，conversationId={}, interruptId={}, round={}, taskCount={}, summary={}",
                            state.getConversationId(),
                            interruptId,
                            state.getRound(),
                            plan.size(),
                            summarizePlan(plan));
                    state.add(new AssistantMessage(planText));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                        log.info("恢复执行阶段未生成可执行任务，直接进入总结。conversationId={}, interruptId={}, round={}",
                                state.getConversationId(),
                                interruptId,
                                state.getRound());
                        emitStep(sink, state.getConversationId(), "execution", "Execution", "当前无需执行工具任务，进入总结阶段。");
                        break;
                    }

                    Map<String, TaskResult> results = executePlan(plan, state, runtimeSystemPrompt);
                    emitStep(sink, state.getConversationId(), "task", "Task Result", renderTaskResultsForDisplay(results));

                    if (state.getInterruptedCheckpoint() != null) {
                        enrichInterruptedCheckpoint(state, plan, results, runtimeSystemPrompt);
                        interruptedByHitl.set(true);
                        sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                        hasSentFinalResult.set(true);
                        sink.tryEmitComplete();
                        return;
                    }

                    if (!shouldContinueAfterCritique(sink, state, results)) {
                        break;
                    }
                }

                if (state.round == maxRounds) {
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
    @SuppressWarnings("unchecked")
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
            Map<String, Object> parsed = JSONUtil.toBean(editedFeedback.arguments(), LinkedHashMap.class);
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
    private OverAllState restoreResumeState(Long userId, String conversationId, ResumeContext resumeContext) {
        OverAllState state = new OverAllState(userId, conversationId, resumeContext.question());
        state.messages.addAll(resumeContext.messages() == null ? List.of() : resumeContext.messages());
        state.executedTasks.putAll(resumeContext.executedTasks() == null ? Map.of() : resumeContext.executedTasks());
        state.approvedToolCallIds.addAll(resumeContext.approvedToolCallIds() == null ? List.of() : resumeContext.approvedToolCallIds());
        state.setSkillRuntimeContexts(resumeContext.skillRuntimeContexts());
        state.round = resumeContext.round();
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
    private TaskResult resumeInterruptedTask(String interruptId, PlanTask currentTask, OverAllState state) {
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
                        5,
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
     * 根据批判结果判断是否需要进入下一轮规划。
     *
     * @param sink 当前事件流 sink。
     * @param state 当前执行状态。
     * @param results 当前轮任务结果。
     * @return 需要继续下一轮时返回 true。
     */
    private boolean shouldContinueAfterCritique(Sinks.Many<AgentStreamEvent> sink,
                                                OverAllState state,
                                                Map<String, TaskResult> results) {
        CritiqueResult critique = critique(state);
        if (critique.passed() || CritiqueResult.ACTION_SUMMARIZE.equals(critique.action())) {
            log.info("批判阶段判断目标已满足，准备进入总结。conversationId={}, round={}",
                    state.getConversationId(),
                    state.getRound());
            emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
            return false;
        }

        if (CritiqueResult.ACTION_ASK_USER.equals(critique.action())) {
            log.info("批判阶段判断需用户补充信息，停止规划。conversationId={}, round={}, feedback={}",
                    state.getConversationId(),
                    state.getRound(),
                    abbreviate(critique.feedback(), 200));
            emitStep(sink, state.getConversationId(), "critique", "Critique", "需要用户补充信息。");
            state.add(new AssistantMessage("【Critique Feedback】\n" + critique.feedback()));
            return false;
        }

        if (shouldStopAfterSkillResourceExhausted(state)) {
            log.info("批判阶段判断 skill 资源已穷尽，停止继续规划。conversationId={}, round={}",
                    state.getConversationId(),
                    state.getRound());
            emitStep(sink, state.getConversationId(), "critique", "Critique", "已确认 skill 可读资源已穷尽，停止无效重试并直接总结。");
            return false;
        }

        log.info("批判阶段要求继续下一轮规划。conversationId={}, round={}, feedback={}",
                state.getConversationId(),
                state.getRound(),
                abbreviate(critique.feedback(), 200));
        state.add(new AssistantMessage("【Critique Feedback】\n" + critique.feedback()));
        emitStep(sink, state.getConversationId(), "critique", "Critique Feedback", critique.feedback());
        compressIfNeeded(state);
        return true;
    }

    /**
     * 记录本次 HITL 中被批准继续执行的 tool call id。
     *
     * @param state 当前执行状态。
     * @param checkpoint 当前 checkpoint。
     */
    private void rememberApprovedToolCallIds(OverAllState state, HitlCheckpointVO checkpoint) {
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
                                         OverAllState state,
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
        if (StringUtils.isNotBlank(left.id()) && StringUtils.isNotBlank(right.id())) {
            return StringUtils.equals(left.id(), right.id());
        }
        return StringUtils.equals(left.toolName(), right.toolName())
                && Objects.equals(left.arguments(), right.arguments())
                && left.order() == right.order();
    }

    /**
     * 执行 `execute Structured Tool Task` 对应逻辑。
     *
     * @param task task 参数。
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private TaskResult executeStructuredToolTask(PlanTask task, OverAllState state) {
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
        String argsJson = JSONUtil.toJsonStr(requestArguments);
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
    private PlanTask repairStructuredToolTask(PlanTask task, OverAllState state) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return task;
        }
        Map<String, Object> templateArguments = resolveStructuredToolTemplate(task);
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

    private Map<String, Object> resolveStructuredToolTemplate(PlanTask task) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return Map.of();
        }
        if (!JsonArgumentUtils.EXECUTE_SKILL_SCRIPT_TOOL.equals(task.toolName())) {
            return Map.of();
        }

        SkillRuntimeContext runtimeSkillContext = findMatchingRuntimeSkillContext(task);
        if (runtimeSkillContext != null) {
            return toExecuteSkillTemplate(runtimeSkillContext);
        }
        return Map.of();
    }

    private SkillRuntimeContext findMatchingRuntimeSkillContext(PlanTask task) {
        if (runtimeSkillContexts == null || runtimeSkillContexts.isEmpty()) {
            return null;
        }
        List<SkillRuntimeContext> matchedContexts = runtimeSkillContexts.stream()
                .filter(Objects::nonNull)
                .toList();
        if (matchedContexts.size() == 1) {
            return matchedContexts.get(0);
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
    private HitlCheckpointVO createDirectToolCheckpoint(OverAllState state,
                                                        String toolName,
                                                        String argumentsJson,
                                                        String description) {
        if (state == null || hitlCheckpointService == null) {
            throw new IllegalStateException("hitlCheckpointService 未配置，无法创建直接工具确认 checkpoint");
        }
        String toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
        String enrichedArgumentsJson = enrichHitlArgumentsJson(toolName, argumentsJson);
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                toolCallId,
                "function",
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
            cn.hutool.json.JSONObject jsonObject = JSONUtil.parseObj(argumentsJson);
            if (jsonObject.containsKey("argumentSpecs")) {
                return argumentsJson;
            }
            String skillName = jsonObject.getStr("skillName");
            String scriptPath = jsonObject.getStr("scriptPath");
            if (StringUtils.isAnyBlank(skillName, scriptPath)) {
                return argumentsJson;
            }
            Map<String, SkillArgumentSpec> argumentSpecs = skillService.resolveSkillArgumentSpecs(skillName, scriptPath);
            if (argumentSpecs == null || argumentSpecs.isEmpty()) {
                return argumentsJson;
            }
            jsonObject.set("argumentSpecs", argumentSpecs);
            return jsonObject.toString();
        } catch (Exception ex) {
            log.warn("补充 HITL argumentSpecs 失败，toolName={}, message={}", toolName, ex.getMessage());
            return argumentsJson;
        }
    }

    /**
     * 查找 `find Tool` 对应结果。
     *
     * @param toolName toolName 参数。
     * @return 返回处理结果。
     */
    private ToolCallback findTool(String toolName) {
        if (tools == null || StringUtils.isBlank(toolName)) {
            return null;
        }
        return tools.stream()
                .filter(tool -> tool != null && tool.getToolDefinition() != null)
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 处理 `enrich Interrupted Checkpoint` 对应逻辑。
     *
     * @param state state 参数。
     * @param plan plan 参数。
     * @param results results 参数。
     * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
     */
    private void enrichInterruptedCheckpoint(OverAllState state,
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
    private void appendTaskResultMessage(OverAllState state, PlanTask task, TaskResult result) {
        if (state == null || task == null || result == null) {
            return;
        }
        String argumentsJson = task.arguments() == null ? "{}" : JSONUtil.toJsonStr(task.arguments());
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
    private String renderExecutedTaskHistory(OverAllState state) {
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
                        snapshot.arguments() == null ? "{}" : JSONUtil.toJsonStr(snapshot.arguments())))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 处理 `should Stop After Skill Resource Exhausted` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private boolean shouldStopAfterSkillResourceExhausted(OverAllState state) {
        return false;
    }

    /**
     * 处理 `critique` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    private CritiqueResult critique(OverAllState state) {

        /**
         * `` 类型实现。
         */
        BeanOutputConverter<CritiqueResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PlanExecutePrompts.getCritiquePrompt()),
                new UserMessage(renderMessages(state.getMessages()))
        ));
        String raw = chatModel.call(prompt).getResult().getOutput().getText();

        return converter.convert(raw);
    }

    /**
     * 处理 `compress If Needed` 对应逻辑。
     *
     * @param state state 参数。
     */
    private void compressIfNeeded(OverAllState state) {

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
                               OverAllState state,
                               StringBuilder finalAnswerBuffer,
                               AtomicBoolean hasSentFinalResult) {
        Prompt prompt = buildSummarizePrompt(state);
        String conversationId = state == null ? null : state.getConversationId();

        chatClient.prompt(prompt)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
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
    private String summarize(OverAllState state) {
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
                                       OverAllState state,
                                       StringBuilder finalAnswerBuffer,
                                       AtomicBoolean hasSentFinalResult) {
        if (hasSentFinalResult.get()) {
            return;
        }
        String answer = finalAnswerBuffer.toString();
        addAssistantMemory(state.conversationId, answer);
        sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(state.getConversationId(), answer));
        hasSentFinalResult.set(true);
        sink.tryEmitComplete();
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
    private void logTaskResult(OverAllState state, PlanTask task, TaskResult result) {
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
    private Prompt buildSummarizePrompt(OverAllState state) {
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
    private String renderLatestConfirmedArguments(OverAllState state) {
        if (state == null || state.getExecutedTasks().isEmpty()) {
            return "无";
        }
        return state.getExecutedTasks().values().stream()
                .sorted(Comparator.comparingInt(ExecutedTaskSnapshot::round).reversed())
                .map(ExecutedTaskSnapshot::arguments)
                .filter(Objects::nonNull)
                .filter(arguments -> !arguments.isEmpty())
                .findFirst()
                .map(JSONUtil::toJsonStr)
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
     * `OverAllState` 类型实现。
     */
    @Getter
    public static class OverAllState {

        private final Long userId;
        private final String conversationId;
        private final String question;
        private final List<Message> messages = new ArrayList<>();
        private final Map<String, ExecutedTaskSnapshot> executedTasks = new LinkedHashMap<>();
        private final Set<String> approvedToolCallIds = new LinkedHashSet<>();
        private List<SkillRuntimeContext> skillRuntimeContexts = List.of();
        private HitlCheckpointVO interruptedCheckpoint;
        private int round = 0;

        /**
         * 创建 `OverAllState` 实例。
         *
         * @param userId userId 参数。
         * @param conversationId conversationId 参数。
         * @param question question 参数。
         */
        public OverAllState(Long userId, String conversationId, String question) {
            this.userId = userId;
            this.question = question;
            this.conversationId = conversationId;
        }

        /**
         * 处理 `next Round` 对应逻辑。
         */
        public void nextRound() {
            round++;
        }

        /**
         * 处理 `add` 对应逻辑。
         *
         * @param m m 参数。
         */
        public void add(Message m) {
            messages.add(m);
        }

        /**
         * 处理 `current Chars` 对应逻辑。
         *
         * @return 返回处理结果。
         */
        public int currentChars() {
            return messages.stream()
                    .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                    .sum();
        }

        /**
         * 处理 `clear Messages` 对应逻辑。
         */
        public void clearMessages() {
            messages.clear();
        }

        /**
         * 获取 `get Approved Tool Call Ids` 对应结果。
         *
         * @return 返回处理结果。
         */
        public Set<String> getApprovedToolCallIds() {
            return approvedToolCallIds;
        }

        /**
         * 设置 `set Interrupted Checkpoint` 对应值。
         *
         * @param interruptedCheckpoint interruptedCheckpoint 参数。
         */
        public void setInterruptedCheckpoint(HitlCheckpointVO interruptedCheckpoint) {
            this.interruptedCheckpoint = interruptedCheckpoint;
        }

        public void setSkillRuntimeContexts(List<SkillRuntimeContext> skillRuntimeContexts) {
            this.skillRuntimeContexts = skillRuntimeContexts == null ? List.of() : skillRuntimeContexts;
        }

        /**
         * 处理 `record Task` 对应逻辑。
         *
         * @param task task 参数。
         * @param result result 参数。
         */
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
                    round
            ));
        }
    }
}
