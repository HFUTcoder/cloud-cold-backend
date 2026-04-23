package com.shenchen.cloudcoldagent.agent;

import cn.hutool.json.JSONUtil;
import com.shenchen.cloudcoldagent.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.PlanExecutePromptsFactory;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.utils.PlanExecuteResumeUtils;
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


@Slf4j
public class PlanExecuteAgent extends BaseAgent {

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

    private final String agentType;

    private PlanExecutePromptsFactory planExecutePrompts;

    private final ChatClient chatClient;

    public PlanExecuteAgent(ChatModel chatModel,
                            List<ToolCallback> tools,
                            List<Advisor> advisors,
                            int maxRounds,
                            int contextCharLimit,
                            int maxToolRetries,
                            PlanExecutePromptsFactory planExecutePrompts,
                            ChatMemory chatMemory,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            String agentType,
                            Set<String> hitlInterceptToolNames) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.contextCharLimit = contextCharLimit;
        this.maxToolRetries = maxToolRetries;
        this.toolSemaphore = new Semaphore(3);
        this.planExecutePrompts = planExecutePrompts;
        this.hitlExecutionService = hitlExecutionService;
        this.hitlCheckpointService = hitlCheckpointService;
        this.hitlResumeService = hitlResumeService;
        this.agentType = StringUtils.isBlank(agentType) ? "PlanExecuteAgent" : agentType;
        this.hitlInterceptToolNames = hitlInterceptToolNames == null ? Set.of() : new LinkedHashSet<>(hitlInterceptToolNames);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (!this.advisors.isEmpty()) {
            builder.defaultAdvisors(this.advisors);
        }
        this.chatClient = builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatModel chatModel;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<Advisor> advisors = new ArrayList<>();

        // 默认迭代5轮
        private int maxRounds = 5;

        // 默认context压缩阈值20000字符
        private int contextCharLimit = 50000;

        // 默认工具重试次数2次
        private int maxToolRetries = 2;

        private PlanExecutePromptsFactory planExecutePrompts;

        private ChatMemory chatMemory;

        private HitlExecutionService hitlExecutionService;

        private HitlCheckpointService hitlCheckpointService;

        private HitlResumeService hitlResumeService;

        private String agentType = "PlanExecuteAgent";

        private Set<String> hitlInterceptToolNames = new LinkedHashSet<>();

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder hitlExecutionService(HitlExecutionService hitlExecutionService) {
            this.hitlExecutionService = hitlExecutionService;
            return this;
        }

        public Builder hitlCheckpointService(HitlCheckpointService hitlCheckpointService) {
            this.hitlCheckpointService = hitlCheckpointService;
            return this;
        }

        public Builder hitlResumeService(HitlResumeService hitlResumeService) {
            this.hitlResumeService = hitlResumeService;
            return this;
        }

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder hitlInterceptToolNames(Set<String> hitlInterceptToolNames) {
            this.hitlInterceptToolNames = hitlInterceptToolNames == null ? new LinkedHashSet<>() : new LinkedHashSet<>(hitlInterceptToolNames);
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(advisors);
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(advisors));
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder contextCharLimit(int contextCharLimit) {
            this.contextCharLimit = contextCharLimit;
            return this;
        }

        public Builder maxToolRetries(int maxToolRetries) {
            this.maxToolRetries = maxToolRetries;
            return this;
        }

        public Builder planExecutePrompts(PlanExecutePromptsFactory planExecutePrompts) {
            this.planExecutePrompts = planExecutePrompts;
            return this;
        }

        public PlanExecuteAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");
            return new PlanExecuteAgent(chatModel, tools, advisors, maxRounds, contextCharLimit, maxToolRetries,
                    planExecutePrompts, chatMemory, hitlExecutionService, hitlCheckpointService, hitlResumeService,
                    agentType, hitlInterceptToolNames);
        }
    }

    public String call(String question) {
        return callInternal(null, question, null, question);
    }

    public String call(String conversationId, String question) {
        return callInternal(conversationId, question, null, question);
    }

    public String call(String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(conversationId, question, runtimeSystemPrompt, question);
    }

    /**
     * 流式输出
     *
     * @param question
     * @return
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return streamInternal(null, question, null, question, List.of());
    }

    // 带会话记忆
    public Flux<AgentStreamEvent> stream(String conversationId, String question) {
        return streamInternal(conversationId, question, null, question, List.of());
    }

    public Flux<AgentStreamEvent> stream(String conversationId, String question, String runtimeSystemPrompt) {
        return streamInternal(conversationId, question, runtimeSystemPrompt, question, List.of());
    }

    public Flux<AgentStreamEvent> stream(String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return streamInternal(conversationId, question, runtimeSystemPrompt, memoryQuestion, List.of());
    }

    public Flux<AgentStreamEvent> stream(String conversationId,
                                         String question,
                                         String runtimeSystemPrompt,
                                         String memoryQuestion,
                                         List<PlanTask> preferredPlan) {
        return streamInternal(conversationId, question, runtimeSystemPrompt, memoryQuestion, preferredPlan);
    }

    public Flux<AgentStreamEvent> resume(String interruptId) {
        return resumeInternal(interruptId);
    }

    public Flux<AgentStreamEvent> streamInternal(String conversationId,
                                                 String question,
                                                 String runtimeSystemPrompt,
                                                 String memoryQuestion,
                                                 List<PlanTask> preferredPlan) {
        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(conversationId, question, preferredPlan);

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
                    log.info("===== Plan-Execute Round {} =====", state.getRound());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    // 1.生成计划
                    List<PlanTask> plan = sanitizePlan(generatePlan(state));
                    String planText = "【Execution Plan】\n" + plan;
                    log.info(planText);
                    state.add(new AssistantMessage(planText));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                        log.info("===== No execution needed, direct answer =====");
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

                    if (critique.passed()) {
                        log.info("===== Goal satisfied, finish =====");
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
                        break;
                    }

                    // 【新增逻辑】简单 heuristic 判断：如果反馈里只说“缺报告/缺总结”，且已经有工具结果了，就不继续规划了
                    boolean isOnlyMissingSummary = critique.feedback() != null
                            && (critique.feedback().contains("报告") || critique.feedback().contains("总结") || critique.feedback().contains("最终答案"))
                            && results != null && !results.isEmpty();

                    if (isOnlyMissingSummary) {
                        log.info("===== 数据已收集完毕，直接进入总结阶段 =====");
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "已收集到足够数据，直接生成最终总结。");
                        break;
                    }

                    if (shouldStopAfterSkillResourceExhausted(state)) {
                        log.info("===== Skill resources exhausted, stop retrying and summarize =====");
                        emitStep(sink, state.getConversationId(), "critique", "Critique", "已确认 skill 可读资源已穷尽，停止无效重试并直接总结。");
                        break;
                    }

                    log.info("===== critique Goal not satisfied, continue round =====,\n reason is {} ", critique.feedback);
                    state.add(new AssistantMessage(critiqueText));
                    emitStep(sink, state.getConversationId(), "critique", "Critique Feedback", critique.feedback());

                    // 4. 压缩context
                    compressIfNeeded(state);
                }
                if (state.round == maxRounds) {
                    log.info("===== Max rounds reached, force finish =====");
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
                        log.info("执行链已因 HITL 中断，等待用户处理，conversationId={}", conversationId);
                        return;
                    }
                    log.info("最终答案: {}", finalAnswerBuffer);
                });
    }

    private void emitStep(Sinks.Many<AgentStreamEvent> sink, String conversationId, String type, String title, String content) {
        if (sink == null || StringUtils.isBlank(content)) {
            return;
        }
        Map<String, Object> stepData = new LinkedHashMap<>();
        stepData.put("stage", type);
        stepData.put("title", title);
        stepData.put("content", content);
        sink.tryEmitNext(AgentStreamEvent.builder()
                .type("thinking_step")
                .conversationId(conversationId)
                .data(stepData)
                .build());
    }

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

    public String call(String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return callInternal(conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    public String callInternal(String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {

        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(conversationId, question, List.of());

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
            log.info("===== Plan-Execute Round {} =====", state.getRound());

            List<PlanTask> plan = sanitizePlan(generatePlan(state));
            log.info("【Execution Plan】\n\n" + plan);
            state.add(new AssistantMessage("【Execution Plan】\n" + plan));

            if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                log.info("===== No execution needed, direct answer =====");
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

            if (critique.passed()) {
                log.info("===== Goal satisfied, finish =====");
                break;
            }

            // --- 修改点：逻辑同流式 ---
            boolean isOnlyMissingSummary = critique.feedback() != null
                    && (critique.feedback().contains("报告") || critique.feedback().contains("总结") || critique.feedback().contains("最终答案"))
                    && hasExecutedTools;

            if (isOnlyMissingSummary) {
                log.info("===== 数据已收集完毕，跳过规划直接总结 =====");
                break;
            }

            if (shouldStopAfterSkillResourceExhausted(state)) {
                log.info("===== Skill resources exhausted, stop retrying and summarize =====");
                break;
            }

            log.info("===== critique Goal not satisfied, continue round =====,\n reason is {} ", critique.feedback);
            state.add(new AssistantMessage(String.format("【Critique Feedback】%n%s", critique.feedback())));

            // 4. 压缩context
            compressIfNeeded(state);
        }
        if (state.round == maxRounds)
            log.info("===== Max rounds reached, force finish =====");

        // 5.总结输出
        return summarize(state);
    }

    private List<PlanTask> generatePlan(OverAllState state) {
        List<PlanTask> preferredPlan = state == null ? List.of() : state.consumePreferredPlan();
        if (!preferredPlan.isEmpty()) {
            return preferredPlan;
        }

        String toolDesc = renderToolDescriptions();
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        String planPrompt = PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).formatPlanPrompt(
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                state.round,
                toolDesc,
                renderExecutedTaskHistory(state),
                converter.getFormat()
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(planPrompt),
                new UserMessage("【对话历史】\n\n" + renderMessages(state.getMessages()))
        ));

        String json = chatModel.call(prompt).getResult().getOutput().getText();

        List<PlanTask> planTasks = converter.convert(json);
        return planTasks;
    }

    private List<PlanTask> sanitizePlan(List<PlanTask> plan) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .filter(task -> task.id() == null || StringUtils.isNotBlank(task.toolName()))
                .toList();
    }

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


    private Map<String, TaskResult> executePlan(List<PlanTask> plan, OverAllState state, String runtimeSystemPrompt) {

        Map<String, TaskResult> results = new ConcurrentHashMap<>();

        // 按 order 分组：order 相同的 task 可并行
        Map<Integer, List<PlanTask>> grouped =
                plan.stream().collect(Collectors.groupingBy(PlanTask::order));

        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();

        // 按 order 顺序执行（不同 order 串行）
        for (Integer order : new TreeSet<>(grouped.keySet())) {

            // 保存当前工具执行快照
            String dependencySnapshot = renderDependencySnapshot(accumulatedResults);

            List<PlanTask> tasks = grouped.get(order);

            List<CompletableFuture<Void>> futures = tasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {

                        try {
                            // 获取执行许可
                            toolSemaphore.acquire();
                            if (task == null || StringUtils.isBlank(task.id())) {
                                return;
                            }
                            TaskResult result = executeWithRetry(task, dependencySnapshot, runtimeSystemPrompt, state);
                            results.put(task.id(), result);
                            state.recordTask(task, result);

                            if (result.success() && result.output() != null) {
                                accumulatedResults.put(task.id(), result.output());
                            }
                            if (result.interrupted() && result.checkpoint() != null && state.getInterruptedCheckpoint() == null) {
                                state.setInterruptedCheckpoint(result.checkpoint());
                            }

                            state.add(new AssistantMessage(String.format(
                                    "【Completed Task Result】%n" +
                                            "taskId: %s%n" +
                                            "success: %s%n" +
                                            "interrupted: %s%n" +
                                            "result:%n%s%n" +
                                            "error:%n%s%n" +
                                            "【End Task Result】",
                                    task.id(),
                                    result.success(),
                                    result.interrupted(),
                                    StringUtils.defaultString(result.output()),
                                    StringUtils.defaultString(result.error())
                            )));

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();

                            results.put(task.id(),
                                    new TaskResult(
                                            task.id(),
                                            false,
                                            false,
                                            null,
                                            "Task execution interrupted",
                                            null
                                    ));
                        } finally {
                            // 释放许可
                            toolSemaphore.release();
                        }

                    }))
                    .toList();

            // 等待当前order组全部完成
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            ).join();

            if (state.getInterruptedCheckpoint() != null) {
                break;
            }
        }

        return results;
    }


    private TaskResult executeWithRetry(PlanTask task, String dependencySnapshot, String runtimeSystemPrompt, OverAllState state) {

        int attempt = 0;
        Throwable lastError = null;

        while (attempt < maxToolRetries) {
            attempt++;
            try {
                return executeStructuredToolTask(task, state);
            } catch (Exception e) {
                lastError = e;
                log.warn("Task {} failed attempt {}/{}", task.id(), attempt, maxToolRetries, e);
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

    private boolean isHitlEnabled(OverAllState state) {
        return state != null
                && hitlExecutionService != null
                && hitlExecutionService.isEnabled(state.getConversationId(), hitlInterceptToolNames);
    }

    private Flux<AgentStreamEvent> resumeInternal(String interruptId) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        AtomicBoolean interruptedByHitl = new AtomicBoolean(false);
        StringBuilder finalAnswerBuffer = new StringBuilder();

        Schedulers.boundedElastic().schedule(() -> {
            try {
                HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(interruptId);
                PlanExecuteResumeUtils.ResumeContext resumeContext = PlanExecuteResumeUtils.readContext(checkpoint.getContext());
                OverAllState state = restoreResumeState(checkpoint.getConversationId(), resumeContext);
                String runtimeSystemPrompt = resumeContext.runtimeSystemPrompt();
                emitStep(sink, state.getConversationId(), "resume", "Resume", "正在恢复上次被 HITL 中断的执行链...");

                PlanTask currentTask = resumeContext.currentTask();
                if (currentTask == null) {
                    throw new IllegalStateException("resume 上下文缺少 currentTask");
                }

                Map<String, TaskResult> currentRoundResults = new LinkedHashMap<>();
                TaskResult resumedTaskResult = resumeInterruptedTask(interruptId, currentTask, state);
                currentRoundResults.put(currentTask.id(), resumedTaskResult);
                rememberApprovedToolNames(state, checkpoint);
                state.recordTask(currentTask, resumedTaskResult);
                appendTaskResultMessage(state, currentTask, resumedTaskResult);

                if (resumedTaskResult.interrupted() && resumedTaskResult.checkpoint() != null) {
                    state.setInterruptedCheckpoint(resumedTaskResult.checkpoint());
                    enrichInterruptedCheckpoint(state, resumeContext.currentPlan(), currentRoundResults, runtimeSystemPrompt);
                    interruptedByHitl.set(true);
                    sink.tryEmitNext(buildHitlInterruptEvent(state.getConversationId(), state.getInterruptedCheckpoint()));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    return;
                }

                List<PlanTask> remainingPlan = excludeCurrentTask(resumeContext.currentPlan(), currentTask);
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
                    log.info("===== Plan-Execute Round {} =====", state.getRound());
                    emitStep(sink, state.getConversationId(), "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    List<PlanTask> plan = sanitizePlan(generatePlan(state));
                    String planText = "【Execution Plan】\n" + plan;
                    log.info(planText);
                    state.add(new AssistantMessage(planText));
                    emitStep(sink, state.getConversationId(), "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                        log.info("===== No execution needed, direct answer =====");
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
                    log.info("===== Max rounds reached, force finish =====");
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
                        log.info("resume 执行链再次因 HITL 中断，等待用户处理，interruptId={}", interruptId);
                        return;
                    }
                    log.info("resume 最终答案: {}", finalAnswerBuffer);
                });
    }

    private OverAllState restoreResumeState(String conversationId, PlanExecuteResumeUtils.ResumeContext resumeContext) {
        OverAllState state = new OverAllState(conversationId, resumeContext.question(), List.of());
        state.messages.addAll(resumeContext.messages() == null ? List.of() : resumeContext.messages());
        state.executedTasks.putAll(resumeContext.executedTasks() == null ? Map.of() : resumeContext.executedTasks());
        state.approvedToolNames.addAll(resumeContext.approvedToolNames() == null ? List.of() : resumeContext.approvedToolNames());
        state.round = resumeContext.round();
        return state;
    }

    private TaskResult resumeInterruptedTask(String interruptId, PlanTask currentTask, OverAllState state) {
        if (hitlResumeService == null) {
            throw new IllegalStateException("hitlResumeService 未配置，无法 resume");
        }
        HitlResumeService.HitlResumeResult resumeResult = hitlResumeService.resume(
                new HitlResumeService.HitlResumeRequest(
                        interruptId,
                        chatModel,
                        tools,
                        advisors,
                        5,
                        hitlInterceptToolNames,
                        state == null ? Set.of() : state.getApprovedToolNames()
                )
        );
        if (resumeResult.interrupted()) {
            return new TaskResult(currentTask == null ? null : currentTask.id(), false, true, null, resumeResult.error(), resumeResult.checkpoint());
        }
        return new TaskResult(currentTask == null ? null : currentTask.id(), true, false, resumeResult.content(), resumeResult.error(), null);
    }

    private boolean shouldContinueAfterCritique(Sinks.Many<AgentStreamEvent> sink,
                                                OverAllState state,
                                                Map<String, TaskResult> results) {
        CritiqueResult critique = critique(state);
        if (critique.passed()) {
            log.info("===== Goal satisfied, finish =====");
            emitStep(sink, state.getConversationId(), "critique", "Critique", "目标已满足，进入总结阶段。");
            return false;
        }

        boolean isOnlyMissingSummary = critique.feedback() != null
                && (critique.feedback().contains("报告") || critique.feedback().contains("总结") || critique.feedback().contains("最终答案"))
                && results != null && !results.isEmpty();

        if (isOnlyMissingSummary) {
            log.info("===== 数据已收集完毕，直接进入总结阶段 =====");
            emitStep(sink, state.getConversationId(), "critique", "Critique", "已收集到足够数据，直接生成最终总结。");
            return false;
        }

        if (shouldStopAfterSkillResourceExhausted(state)) {
            log.info("===== Skill resources exhausted, stop retrying and summarize =====");
            emitStep(sink, state.getConversationId(), "critique", "Critique", "已确认 skill 可读资源已穷尽，停止无效重试并直接总结。");
            return false;
        }

        log.info("===== critique Goal not satisfied, continue round =====,\n reason is {} ", critique.feedback);
        state.add(new AssistantMessage("【Critique Feedback】\n" + critique.feedback()));
        emitStep(sink, state.getConversationId(), "critique", "Critique Feedback", critique.feedback());
        compressIfNeeded(state);
        return true;
    }

    private void rememberApprovedToolNames(OverAllState state, HitlCheckpointVO checkpoint) {
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
            String toolName = StringUtils.defaultIfBlank(feedback.name(), pendingToolCall.name());
            if (StringUtils.isNotBlank(toolName)) {
                state.getApprovedToolNames().add(toolName);
            }
        }
    }

    private void finishStreamWithSummary(Sinks.Many<AgentStreamEvent> sink,
                                         OverAllState state,
                                         StringBuilder finalAnswerBuffer,
                                         AtomicBoolean hasSentFinalResult) {
        emitStep(sink, state.getConversationId(), "final", "Final Answer", "正在生成最终答案...");
        streamSummary(sink, state, finalAnswerBuffer, hasSentFinalResult);
    }

    private List<PlanTask> excludeCurrentTask(List<PlanTask> plan, PlanTask currentTask) {
        List<PlanTask> sanitizedPlan = sanitizePlan(plan);
        if (currentTask == null || sanitizedPlan.isEmpty()) {
            return sanitizedPlan;
        }
        return sanitizedPlan.stream()
                .filter(task -> !isSameTask(task, currentTask))
                .toList();
    }

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

    private TaskResult executeStructuredToolTask(PlanTask task, OverAllState state) {
        if (task == null || StringUtils.isBlank(task.toolName())) {
            return new TaskResult(task == null ? null : task.id(), false, false, null, "结构化任务缺少 toolName", null);
        }
        ToolCallback callback = findTool(task.toolName());
        if (callback == null) {
            return new TaskResult(task.id(), false, false, null, "工具未找到：" + task.toolName(), null);
        }
        Map<String, Object> requestArguments = task.arguments() == null ? Map.of() : task.arguments();
        String argsJson = JSONUtil.toJsonStr(requestArguments);
        log.info("===== Direct execute structured tool task, taskId={}, tool={}, arguments={} =====",
                task.id(), task.toolName(), argsJson);

        try {
            if (isHitlEnabled(state)
                    && hitlInterceptToolNames.contains(task.toolName())
                    && !state.hasApprovedToolName(task.toolName())) {
                HitlCheckpointVO checkpoint = createDirectToolCheckpoint(
                        state,
                        task.toolName(),
                        argsJson,
                        "该工具需要用户手动确认。已锁定结构化 toolName 和 arguments。"
                );
                return new TaskResult(task.id(), false, true, null, "Task execution interrupted by HITL", checkpoint);
            }

            String result = String.valueOf(callback.call(argsJson));
            return new TaskResult(task.id(), true, false, result, null, null);
        } catch (Exception e) {
            return new TaskResult(task.id(), false, false, null, e.getMessage(), null);
        }
    }

    private HitlCheckpointVO createDirectToolCheckpoint(OverAllState state,
                                                        String toolName,
                                                        String argumentsJson,
                                                        String description) {
        if (state == null || hitlCheckpointService == null) {
            throw new IllegalStateException("hitlCheckpointService 未配置，无法创建直接工具确认 checkpoint");
        }
        String toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                toolCallId,
                "function",
                toolName,
                argumentsJson
        );

        List<Message> checkpointMessages = new ArrayList<>(state.getMessages());
        checkpointMessages.add(AssistantMessage.builder().toolCalls(List.of(toolCall)).build());

        PendingToolCall pendingToolCall = new PendingToolCall(
                toolCallId,
                toolName,
                argumentsJson,
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

    private void appendTaskResultMessage(OverAllState state, PlanTask task, TaskResult result) {
        if (state == null || task == null || result == null) {
            return;
        }
        state.add(new AssistantMessage(String.format(
                "【Completed Task Result】%n" +
                        "taskId: %s%n" +
                        "success: %s%n" +
                        "interrupted: %s%n" +
                        "result:%n%s%n" +
                        "error:%n%s%n" +
                        "【End Task Result】",
                task.id(),
                result.success(),
                result.interrupted(),
                StringUtils.defaultString(result.output()),
                StringUtils.defaultString(result.error())
        )));
    }

    private AgentStreamEvent buildHitlInterruptEvent(String conversationId, HitlCheckpointVO checkpoint) {
        if (checkpoint == null) {
            return AgentStreamEvent.builder()
                    .type("hitl_interrupt")
                    .conversationId(conversationId)
                    .data(Map.of())
                    .build();
        }
        Map<String, Object> interruptData = new LinkedHashMap<>();
        interruptData.put("agentType", checkpoint.getAgentType());
        interruptData.put("pendingToolCalls", checkpoint.getPendingToolCalls());
        interruptData.put("status", checkpoint.getStatus());
        return AgentStreamEvent.builder()
                .type("hitl_interrupt")
                .conversationId(conversationId)
                .interruptId(checkpoint.getInterruptId())
                .data(interruptData)
                .build();
    }

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

    private boolean shouldStopAfterSkillResourceExhausted(OverAllState state) {
        return false;
    }

    private CritiqueResult critique(OverAllState state) {

        BeanOutputConverter<CritiqueResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getCritiquePrompt()),
                new UserMessage(renderMessages(state.getMessages()))
        ));
        String raw = chatModel.call(prompt).getResult().getOutput().getText();

        return converter.convert(raw);
    }

    private void compressIfNeeded(OverAllState state) {

        if (state.currentChars() < contextCharLimit) {
            return;
        }

        log.warn("===== Context too large, compressing ,size is {} =====", state.currentChars());

        String compressLimitPrompt = String.format(
                "## 最大压缩限制（必须遵守）%n" +
                        "- 你输出的最终内容【总字符数（包含所有标签、空格、换行）】%n" +
                        "  不得超过：%s%n" +
                        "- 这是硬性上限，不是建议%n" +
                        "- 如超过该限制，视为压缩失败%n%n%s",
                contextCharLimit,
                PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getCompressPrompt()
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(compressLimitPrompt),

                new UserMessage(renderMessages(state.getMessages()))
        ));

        String snapshot = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getText();

        state.clearMessages();
        state.add(new SystemMessage("【Compressed Agent State】\n" + snapshot));
        log.warn("===== Context compress has completed, size is {} =====", state.currentChars());
    }


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

    private String summarize(OverAllState state) {
        Prompt prompt = buildSummarizePrompt(state);
        String answer = chatModel.call(prompt).getResult().getOutput().getText();
        addAssistantMemory(state.conversationId, answer);
        return answer;
    }

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
        Map<String, Object> deltaData = new LinkedHashMap<>();
        deltaData.put("content", text);
        sink.tryEmitNext(AgentStreamEvent.builder()
                .type("assistant_delta")
                .conversationId(conversationId)
                .data(deltaData)
                .build());
    }

    private void completeSummaryStream(Sinks.Many<AgentStreamEvent> sink,
                                       OverAllState state,
                                       StringBuilder finalAnswerBuffer,
                                       AtomicBoolean hasSentFinalResult) {
        if (hasSentFinalResult.get()) {
            return;
        }
        String answer = finalAnswerBuffer.toString();
        addAssistantMemory(state.conversationId, answer);
        Map<String, Object> finalData = new LinkedHashMap<>();
        finalData.put("content", answer);
        sink.tryEmitNext(AgentStreamEvent.builder()
                .type("final_answer")
                .conversationId(state.getConversationId())
                .data(finalData)
                .build());
        hasSentFinalResult.set(true);
        sink.tryEmitComplete();
    }

    private Prompt buildSummarizePrompt(OverAllState state) {
        // 1. 先获取外部 Prompt
        String externalSummarizePrompt = PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getSummarizePrompt();

        // 2. 构建系统提示词：先拼接固定部分和外部 Prompt，不在这里用 formatted
        String systemMessageContent =
                "## 核心规则（必须100%严格遵守）\n" +
                        "1.  你必须完全、仅基于下方【执行上下文】里的工具返回的真实数据生成最终答案，禁止编造任何不在上下文里的日期、天气、数值、景点等信息。\n" +
                        "2.  禁止输出【Execution Plan】【Critique Feedback】【Task Result】等任何中间执行过程的标签和内容，只输出给用户看的最终答案。\n" +
                        "3.  输出内容必须连贯、完整，符合用户的原始问题要求，禁止重复内容。\n" +
                        "4.  如果上下文里的工具数据有明确的时间，必须以工具返回的时间为准，禁止使用与当前时间不符的虚假日期。\n\n" +
                        externalSummarizePrompt;

        // 3. 构建用户消息：这里只有两个变量，安全地使用 formatted
        String userMessageContent = String.format(
                "【用户原始问题】%n%s%n%n【执行上下文（含工具返回的真实结果）】%n%s",
                state.getQuestion(),
                renderMessages(state.getMessages())
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessageContent),
                new UserMessage(userMessageContent)
        ));
        return prompt;
    }

    private String renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n")
                    .append(m.getText());
        }
        return sb.toString();
    }

    private String defaultTaskValue(String value) {
        return StringUtils.defaultIfBlank(value, "-");
    }

    // 内部对象实体
    public record PlanTask(String id,
                           String toolName,
                           Map<String, Object> arguments,
                           int order,
                           String summary) {
    }

    public record CritiqueResult(boolean passed, String feedback) {
    }

    @Getter
    public static class OverAllState {

        private final String conversationId;
        private final String question;
        private final List<Message> messages = new ArrayList<>();
        private final Map<String, ExecutedTaskSnapshot> executedTasks = new LinkedHashMap<>();
        private final Set<String> approvedToolNames = new LinkedHashSet<>();
        private final List<PlanTask> preferredPlan = new ArrayList<>();
        private HitlCheckpointVO interruptedCheckpoint;
        private int round = 0;
        private boolean preferredPlanConsumed;

        public OverAllState(String conversationId, String question, List<PlanTask> preferredPlan) {
            this.question = question;
            this.conversationId = conversationId;
            if (preferredPlan != null) {
                this.preferredPlan.addAll(preferredPlan);
            }
        }

        public void nextRound() {
            round++;
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

        public Set<String> getApprovedToolNames() {
            return approvedToolNames;
        }

        public boolean hasApprovedToolName(String toolName) {
            return toolName != null && approvedToolNames.contains(toolName);
        }

        public List<PlanTask> consumePreferredPlan() {
            if (preferredPlanConsumed || preferredPlan.isEmpty()) {
                return List.of();
            }
            preferredPlanConsumed = true;
            return new ArrayList<>(preferredPlan);
        }

        public void setInterruptedCheckpoint(HitlCheckpointVO interruptedCheckpoint) {
            this.interruptedCheckpoint = interruptedCheckpoint;
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
                    round
            ));
        }
    }


    public record TaskResult(
            String taskId,
            boolean success,
            boolean interrupted,
            String output,
            String error,
            HitlCheckpointVO checkpoint) { }

    public record ExecutedTaskSnapshot(
            String taskId,
            String toolName,
            Map<String, Object> arguments,
            String summary,
            boolean success,
            String output,
            String error,
            int round) { }

}
