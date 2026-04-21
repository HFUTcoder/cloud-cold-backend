package com.shenchen.cloudcoldagent.agent;

import com.shenchen.cloudcoldagent.prompts.PlanExecutePromptsFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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

    private PlanExecutePromptsFactory planExecutePrompts;

    public PlanExecuteAgent(ChatModel chatModel,
                            List<ToolCallback> tools,
                            List<Advisor> advisors,
                            int maxRounds,
                            int contextCharLimit,
                            int maxToolRetries,
                            PlanExecutePromptsFactory planExecutePrompts,
                            ChatMemory chatMemory) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.contextCharLimit = contextCharLimit;
        this.maxToolRetries = maxToolRetries;
        this.toolSemaphore = new Semaphore(3);
        this.planExecutePrompts = planExecutePrompts;
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

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
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
            return new PlanExecuteAgent(chatModel, tools, advisors, maxRounds, contextCharLimit, maxToolRetries, planExecutePrompts, chatMemory);
        }
    }

    public String call(String question) {
        return callInternal(null, question, null);
    }

    public String call(String conversationId, String question) {
        return callInternal(conversationId, question, null);
    }

    public String call(String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(conversationId, question, runtimeSystemPrompt);
    }

    /**
     * 流式输出
     *
     * @param question
     * @return
     */
    public Flux<String> stream(String question) {
        return streamInternal(null, question, null);
    }

    // 带会话记忆
    public Flux<String> stream(String conversationId, String question) {
        return streamInternal(conversationId, question, null);
    }

    public Flux<String> stream(String conversationId, String question, String runtimeSystemPrompt) {
        return streamInternal(conversationId, question, runtimeSystemPrompt);
    }

    public Flux<String> streamInternal(String conversationId, String question, String runtimeSystemPrompt) {
        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(conversationId, question);

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
            addUserMemory(conversationId, question);
        }

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        hasSentFinalResult.set(false);

        // 收集最终答案，存储memory
        StringBuilder finalAnswerBuffer = new StringBuilder();

        Schedulers.boundedElastic().schedule(() -> {
            try {
                while (maxRounds <= 0 || state.getRound() < maxRounds) {
                    state.nextRound();
                    log.info("===== Plan-Execute Round {} =====", state.getRound());
                    emitStep(sink, "round", "Plan-Execute Round " + state.getRound(), "开始规划与执行");

                    // 1.生成计划
                    List<PlanTask> plan = filterRepeatedTasks(generatePlan(state), state);
                    String planText = "【Execution Plan】\n" + plan;
                    log.info(planText);
                    state.add(new AssistantMessage(planText));
                    emitStep(sink, "plan", "Execution Plan", renderPlanForDisplay(plan));

                    if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                        log.info("===== No execution needed, direct answer =====");
                        emitStep(sink, "execution", "Execution", "当前无需执行工具任务，进入总结阶段。");
                        break;
                    }

                    // 2.执行
                    Map<String, TaskResult> results = executePlan(plan, state, runtimeSystemPrompt);
                    emitStep(sink, "task", "Task Result", renderTaskResultsForDisplay(results));

                    // 3.批判
                    CritiqueResult critique = critique(state);
                    String critiqueText = "【Critique Feedback】\n" + critique.feedback();

                    if (critique.passed()) {
                        log.info("===== Goal satisfied, finish =====");
                        emitStep(sink, "critique", "Critique", "目标已满足，进入总结阶段。");
                        break;
                    }

                    // 【新增逻辑】简单 heuristic 判断：如果反馈里只说“缺报告/缺总结”，且已经有工具结果了，就不继续规划了
                    boolean isOnlyMissingSummary = critique.feedback() != null
                            && (critique.feedback().contains("报告") || critique.feedback().contains("总结") || critique.feedback().contains("最终答案"))
                            && results != null && !results.isEmpty();

                    if (isOnlyMissingSummary) {
                        log.info("===== 数据已收集完毕，直接进入总结阶段 =====");
                        emitStep(sink, "critique", "Critique", "已收集到足够数据，直接生成最终总结。");
                        break;
                    }

                    if (shouldStopAfterSkillResourceExhausted(state)) {
                        log.info("===== Skill resources exhausted, stop retrying and summarize =====");
                        emitStep(sink, "critique", "Critique", "已确认 skill 可读资源已穷尽，停止无效重试并直接总结。");
                        break;
                    }

                    log.info("===== critique Goal not satisfied, continue round =====,\n reason is {} ", critique.feedback);
                    state.add(new AssistantMessage(critiqueText));
                    emitStep(sink, "critique", "Critique Feedback", critique.feedback());

                    // 4. 压缩context
                    compressIfNeeded(state);
                }
                if (state.round == maxRounds) {
                    log.info("===== Max rounds reached, force finish =====");
                    emitStep(sink, "round", "Plan-Execute", "达到最大轮次，强制进入总结阶段。");
                }

                // 5.总结输出
                emitStep(sink, "final", "Final Answer", "正在生成最终答案...");
                String answer = summarize(state);
                sink.tryEmitNext(answer);
                finalAnswerBuffer.append(answer);

                hasSentFinalResult.set(true);
                sink.tryEmitComplete();
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
                    log.info("最终答案: {}", finalAnswerBuffer);
                });
    }

    private void emitStep(Sinks.Many<String> sink, String type, String title, String content) {
        if (sink == null || StringUtils.isBlank(content)) {
            return;
        }
        String block = """
                [思考过程][%s] %s
                %s
                
                """.formatted(type, title, content);
        sink.tryEmitNext(block);
    }

    private String renderPlanForDisplay(List<PlanTask> plan) {
        if (plan == null || plan.isEmpty()) {
            return "未生成可执行任务。";
        }
        return plan.stream()
                .filter(Objects::nonNull)
                .map(task -> "- taskId: %s, order: %s, instruction: %s".formatted(
                        task.id(),
                        task.order(),
                        task.instruction()))
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

    public String callInternal(String conversationId, String question, String runtimeSystemPrompt) {

        boolean useMemory = useMemory(conversationId);

        OverAllState state = new OverAllState(conversationId, question);

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
            addUserMemory(conversationId, question);
        }

        // 【新增】用于判断是否已经执行过工具
        boolean hasExecutedTools = false;

        while (maxRounds <= 0 || state.getRound() < maxRounds) {
            state.nextRound();
            log.info("===== Plan-Execute Round {} =====", state.getRound());

            List<PlanTask> plan = filterRepeatedTasks(generatePlan(state), state);
            log.info("【Execution Plan】\n\n" + plan);
            state.add(new AssistantMessage("【Execution Plan】\n" + plan));

            if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                log.info("===== No execution needed, direct answer =====");
                break;
            }

            // 2.执行
            Map<String, TaskResult> results = executePlan(plan, state, runtimeSystemPrompt);
            hasExecutedTools = (results != null && !results.isEmpty());

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
            state.add(new AssistantMessage("""
                    【Critique Feedback】
                    %s
                    """.formatted(critique.feedback())));

            // 4. 压缩context
            compressIfNeeded(state);
        }
        if (state.round == maxRounds)
            log.info("===== Max rounds reached, force finish =====");

        // 5.总结输出
        return summarize(state);
    }

    private List<PlanTask> generatePlan(OverAllState state) {

        String toolDesc = renderToolDescriptions();
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("""
                            当前时间是：%s。
                        
                            当前是迭代的第 %s 轮次。

                            ## Skill 资源规划约束（必须严格遵守）
                            1. 如果任务涉及读取某个 skill 下的 references 或 scripts 资源，而你还不知道具体文件名，必须先规划调用 `list_skill_resources`。
                            2. 在没有拿到 `list_skill_resources` 的真实结果前，禁止直接规划 `read_skill_resource` 去猜测 `resourcePath`。
                            3. 禁止生成这类模糊或猜测式参数：
                               - `references`
                               - `scripts`
                               - `references/文件名`
                               - `scripts/文件名`
                               - `文件路径`
                               - `文件内容`
                            4. 只有当 `list_skill_resources` 已返回真实文件清单后，才允许规划 `read_skill_resource`，并且 `resourcePath` 必须使用清单里出现过的真实相对路径。
                            5. 如果清单明确显示某类资源为空，例如 `scripts: 无`，则后续禁止再规划该类资源读取任务。
                        
                            ## 可用工具说明（仅用于规划参考）
                            %s

                            ## 已执行任务摘要（避免重复规划）
                            %s
                        
                            ## 输出format
                            %s
                        
                        """.formatted(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), state.round, toolDesc,
                        renderExecutedTaskHistory(state), converter.getFormat())
                        + PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getPlanPrompt()),
                new UserMessage("【对话历史】\n\n" + renderMessages(state.getMessages()))
        ));

        String json = chatModel.call(prompt).getResult().getOutput().getText();

        List<PlanTask> planTasks = converter.convert(json);
        return planTasks;
    }

    private List<PlanTask> filterRepeatedTasks(List<PlanTask> plan, OverAllState state) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }

        List<PlanTask> filtered = new ArrayList<>();
        Set<String> currentRoundKeys = new HashSet<>();

        for (PlanTask task : plan) {
            if (task == null || StringUtils.isBlank(task.id()) || StringUtils.isBlank(task.instruction())) {
                continue;
            }

            String taskKey = buildTaskKey(task);
            if (!currentRoundKeys.add(taskKey)) {
                log.info("===== Skip duplicated task in same round =====, taskId: {}, instruction: {}", task.id(), task.instruction());
                continue;
            }

            if (state.hasAttempted(taskKey)) {
                ExecutedTaskSnapshot snapshot = state.getExecutedTask(taskKey);
                log.info("===== Skip repeated task across rounds =====, taskId: {}, instruction: {}, previousRound: {}, success: {}",
                        task.id(), task.instruction(), snapshot == null ? null : snapshot.round(), snapshot != null && snapshot.success());
                continue;
            }

            filtered.add(task);
        }

        return filtered;
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
                            TaskResult result = executeWithRetry(task, dependencySnapshot, runtimeSystemPrompt);
                            results.put(task.id(), result);
                            state.recordTask(buildTaskKey(task), task, result);

                            if (result.success() && result.output() != null) {
                                accumulatedResults.put(task.id(), result.output());
                            }

                            state.add(new AssistantMessage("""
                                    【Completed Task Result】
                                    taskId: %s
                                    success: %s
                                    result:
                                    %s
                                    error:
                                    %s
                                    【End Task Result】
                                    """.formatted(
                                    task.id(),
                                    result.success(),
                                    result.output(),
                                    result.error()
                            )));

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();

                            results.put(task.id(),
                                    new TaskResult(
                                            task.id(),
                                            false,
                                            null,
                                            "Task execution interrupted"
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
        }

        return results;
    }


    private TaskResult executeWithRetry(PlanTask task, String dependencySnapshot, String runtimeSystemPrompt) {

        int attempt = 0;
        Throwable lastError = null;

        while (attempt < maxToolRetries) {
            attempt++;
            try {
                SimpleReactAgent agent = SimpleReactAgent.builder()
                        .chatModel(chatModel)
                        .tools(tools)
                        .advisors(advisors)
                        .maxRounds(5)
                        .systemPrompt(PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getExecutePrompt())
                        .build();

                String result = agent.call(null, """
                        【Available Results】
                        %s
                        
                        【Current Task】
                        %s
                        """.formatted(
                        dependencySnapshot.isBlank() ? "NONE" : dependencySnapshot,
                        task.instruction
                ), runtimeSystemPrompt);

                return new TaskResult(task.id(), true, result, null);
            } catch (Exception e) {
                lastError = e;
                log.warn("Task {} failed attempt {}/{}", task.id(), attempt, maxToolRetries, e);
            }
        }

        return new TaskResult(
                task.id(),
                false,
                null,
                lastError == null ? "unknown error" : lastError.getMessage()
        );
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
                .map(snapshot -> "- round: %s, success: %s, instruction: %s".formatted(
                        snapshot.round(),
                        snapshot.success(),
                        snapshot.instruction()))
                .collect(Collectors.joining("\n"));
    }

    private boolean shouldStopAfterSkillResourceExhausted(OverAllState state) {
        if (state == null || state.getExecutedTasks().isEmpty()) {
            return false;
        }

        SkillResourceInventory inventory = buildSkillResourceInventory(state);
        if (!inventory.hasInventory()) {
            return false;
        }

        if (!inventory.scriptsKnownEmpty()) {
            return false;
        }

        if (!inventory.mainRead() && inventory.referenceFiles().isEmpty()) {
            return false;
        }

        boolean allReferencesRead = inventory.referenceFiles().isEmpty()
                || inventory.referenceFiles().stream().allMatch(inventory::hasReadReference);

        if (!allReferencesRead) {
            return false;
        }

        log.info("===== Skill inventory indicates no more script resources to read, mainRead={}, referenceFiles={}, readReferences={} =====",
                inventory.mainRead(), inventory.referenceFiles(), inventory.readReferenceFiles());
        return true;
    }

    private SkillResourceInventory buildSkillResourceInventory(OverAllState state) {
        Set<String> referenceFiles = new LinkedHashSet<>();
        Set<String> scriptFiles = new LinkedHashSet<>();
        Set<String> readReferenceFiles = new LinkedHashSet<>();
        boolean listed = false;
        boolean scriptsKnownEmpty = false;
        boolean mainRead = false;

        for (ExecutedTaskSnapshot snapshot : state.getExecutedTasks().values()) {
            if (snapshot == null || !snapshot.success() || StringUtils.isBlank(snapshot.output())) {
                continue;
            }

            String output = snapshot.output();
            if (output.contains("mainFile: SKILL.md")) {
                listed = true;
                referenceFiles.addAll(extractSectionItems(output, "references:", "scripts:"));
                List<String> scripts = extractSectionItems(output, "scripts:", null);
                scriptFiles.addAll(scripts);
                scriptsKnownEmpty = scripts.isEmpty();
            }

            if (output.contains("resourceType: reference")) {
                String referencePath = extractFieldValue(output, "resourcePath:");
                if (StringUtils.isNotBlank(referencePath)) {
                    readReferenceFiles.add(referencePath);
                }
            }

            if (output.contains("skillName:") && output.contains("content:") && !output.contains("resourceType:")) {
                mainRead = true;
            }
        }

        return new SkillResourceInventory(listed, scriptsKnownEmpty, mainRead, referenceFiles, scriptFiles, readReferenceFiles);
    }

    private List<String> extractSectionItems(String text, String sectionStart, String nextSectionStart) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(sectionStart)) {
            return List.of();
        }

        int startIndex = text.indexOf(sectionStart);
        if (startIndex < 0) {
            return List.of();
        }
        int contentStart = startIndex + sectionStart.length();
        int endIndex = StringUtils.isBlank(nextSectionStart) ? text.length() : text.indexOf(nextSectionStart, contentStart);
        if (endIndex < 0) {
            endIndex = text.length();
        }

        String section = text.substring(contentStart, endIndex).trim();
        if (section.isEmpty() || section.equals("- 无")) {
            return List.of();
        }

        return Arrays.stream(section.split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).trim())
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private String extractFieldValue(String text, String fieldName) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(fieldName)) {
            return null;
        }
        return Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith(fieldName))
                .map(line -> line.substring(fieldName.length()).trim())
                .findFirst()
                .orElse(null);
    }

    private String buildTaskKey(PlanTask task) {
        String instruction = task == null ? "" : StringUtils.defaultString(task.instruction());
        String normalized = instruction
                .replace("调用", "")
                .replace("工具", "")
                .replace("执行", "")
                .replace("查询", "")
                .replace("搜索", "")
                .replace("根据", "")
                .replace("，", "")
                .replace(",", "")
                .replace("。", "")
                .replace(".", "")
                .replace("：", "")
                .replace(":", "")
                .replaceAll("\\s+", "");

        char[] chars = normalized.toCharArray();
        Arrays.sort(chars);
        return new String(chars);
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

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("""                             
                             ## 最大压缩限制（必须遵守）
                             - 你输出的最终内容【总字符数（包含所有标签、空格、换行）】
                                不得超过：%s
                             - 这是硬性上限，不是建议
                             - 如超过该限制，视为压缩失败
                        
                        """.formatted(contextCharLimit) + PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getCompressPrompt()),

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


    private String summarize(OverAllState state) {
        // 1. 先获取外部 Prompt
        String externalSummarizePrompt = PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getSummarizePrompt();

        // 2. 构建系统提示词：先拼接固定部分和外部 Prompt，不在这里用 formatted
        String systemMessageContent = """
                ## 核心规则（必须100%严格遵守）
                1.  你必须完全、仅基于下方【执行上下文】里的工具返回的真实数据生成最终答案，禁止编造任何不在上下文里的日期、天气、数值、景点等信息。
                2.  禁止输出【Execution Plan】【Critique Feedback】【Task Result】等任何中间执行过程的标签和内容，只输出给用户看的最终答案。
                3.  输出内容必须连贯、完整，符合用户的原始问题要求，禁止重复内容。
                4.  如果上下文里的工具数据有明确的时间，必须以工具返回的时间为准，禁止使用与当前时间不符的虚假日期。
                
                """ + externalSummarizePrompt; // 直接拼接，不使用 formatted

        // 3. 构建用户消息：这里只有两个变量，安全地使用 formatted
        String userMessageContent = """
                【用户原始问题】
                %s
                
                【执行上下文（含工具返回的真实结果）】
                %s
                """.formatted(
                state.getQuestion(),
                renderMessages(state.getMessages())
        );

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessageContent),
                new UserMessage(userMessageContent)
        ));

        String answer = chatModel.call(prompt).getResult().getOutput().getText();
        // 追加记忆
        addAssistantMemory(state.conversationId, answer);
        return answer;
    }

    private String renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n")
                    .append(m.getText());
        }
        return sb.toString();
    }

    // 内部对象实体
    public record PlanTask(String id, String instruction, int order) {
    }

    public record CritiqueResult(boolean passed, String feedback) {
    }

    @Getter
    public static class OverAllState {

        private final String conversationId;
        private final String question;
        private final List<Message> messages = new ArrayList<>();
        private final Map<String, ExecutedTaskSnapshot> executedTasks = new LinkedHashMap<>();
        private int round = 0;

        public OverAllState(String conversationId, String question) {
            this.question = question;
            this.conversationId = conversationId;
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

        public void recordTask(String taskKey, PlanTask task, TaskResult result) {
            if (StringUtils.isBlank(taskKey) || task == null || result == null) {
                return;
            }
            executedTasks.put(taskKey, new ExecutedTaskSnapshot(
                    task.id(),
                    task.instruction(),
                    result.success(),
                    result.output(),
                    result.error(),
                    round
            ));
        }

        public boolean hasAttempted(String taskKey) {
            return executedTasks.containsKey(taskKey);
        }

        public ExecutedTaskSnapshot getExecutedTask(String taskKey) {
            return executedTasks.get(taskKey);
        }
    }


    public record TaskResult(
            String taskId,
            boolean success,
            String output,
            String error) { }

    public record ExecutedTaskSnapshot(
            String taskId,
            String instruction,
            boolean success,
            String output,
            String error,
            int round) { }

    private record SkillResourceInventory(
            boolean listed,
            boolean scriptsKnownEmpty,
            boolean mainRead,
            Set<String> referenceFiles,
            Set<String> scriptFiles,
            Set<String> readReferenceFiles) {

        boolean hasInventory() {
            return listed;
        }

        boolean hasReadReference(String path) {
            return readReferenceFiles.contains(path);
        }
    }

}
