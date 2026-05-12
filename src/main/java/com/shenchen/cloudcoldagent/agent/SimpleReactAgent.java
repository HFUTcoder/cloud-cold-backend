package com.shenchen.cloudcoldagent.agent;

import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.ReactAgentPrompts;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
import com.shenchen.cloudcoldagent.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快速模式 Agent，实现单代理 ReAct 推理与工具调用闭环。
 */
@Slf4j
public class SimpleReactAgent extends BaseAgent {

    private static final String REACT_AGENT_SYSTEM_PROMPT = ReactAgentPrompts.STRICT_REACT_SYSTEM_PROMPT;
    private final String name;
    private final String systemPrompt;
    private final Executor toolExecutor;
    private final Executor virtualThreadExecutor;
    private final Semaphore toolSemaphore;
    private ChatClient chatClient;

    /**
     * 初始化快速模式 Agent，并构建其专属的 ChatClient。
     *
     * @param name Agent 名称。
     * @param chatModel 底层对话模型。
     * @param tools 可调用工具集合。
     * @param advisors advisor 集合。
     * @param systemPrompt 业务侧补充的 system prompt。
     * @param maxRounds 最大推理轮次。
     * @param chatMemory 会话记忆对象。
     * @param toolConcurrency 工具并发调用上限。
     * @param toolExecutor 工具调用线程池。
     * @param virtualThreadExecutor 虚拟线程执行器，用于流式 chunk 解耦。
     */
    public SimpleReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, List<Advisor> advisors,
                            String systemPrompt, int maxRounds, ChatMemory chatMemory, int toolConcurrency,
                            Executor toolExecutor, Executor virtualThreadExecutor) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.toolSemaphore = new Semaphore(Math.max(1, toolConcurrency));
        this.toolExecutor = toolExecutor;
        this.virtualThreadExecutor = virtualThreadExecutor;

        initChatClient();

        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    /**
     * 初始化快速模式所使用的 ChatClient，并关闭框架内部的自动工具执行。
     */
    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();

            ChatClient.Builder builder = ChatClient.builder(chatModel);
            builder.defaultOptions(toolOptions).defaultToolCallbacks(tools);
            if (!advisors.isEmpty()) {
                builder.defaultAdvisors(advisors);
            }
            this.chatClient = builder.build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 以无显式用户上下文的方式同步调用快速模式 Agent。
     *
     * @param question 用户问题。
     * @return Agent 最终回答。
     */
    public String call(String question) {
        return callInternal(null, null, question, null, question);
    }

    // 带会话记忆
    /**
     * 以带会话上下文的方式同步调用快速模式 Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @return Agent 最终回答。
     */
    public String call(Long userId, String conversationId, String question) {
        return callInternal(userId, conversationId, question, null, question);
    }

    /**
     * 以带运行时 system prompt 的方式同步调用快速模式 Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户问题。
     * @param runtimeSystemPrompt 动态注入的运行时系统提示词。
     * @return Agent 最终回答。
     */
    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    /**
     * 以完整参数形式同步调用快速模式 Agent。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 动态注入的运行时系统提示词。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return Agent 最终回答。
     */
    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    /**
     * 准备消息列表：加载 system prompt、历史记忆、用户问题，并写入用户记忆。
     */
    private record PreparedMessages(List<Message> messages, boolean useMemory) {}

    private PreparedMessages prepareMessages(String question, String runtimeSystemPrompt,
                                             String memoryQuestion, String conversationId) {
        CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();
        boolean useMemory = useMemory(conversationId);

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));
        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            messages.add(new SystemMessage(runtimeSystemPrompt));
        }

        if (useMemory) {
            List<Message> history = getMemoryMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
        }

        messages.add(new UserMessage(wrapQuestion(question)));

        if (useMemory) {
            addUserMemory(conversationId, memoryQuestion == null ? question : memoryQuestion);
        }

        return new PreparedMessages(messages, useMemory);
    }

    /**
     * 快速模式同步执行主循环，负责加载上下文、驱动模型轮询、处理工具调用并在必要时写入记忆。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 实际发给模型的问题。
     * @param runtimeSystemPrompt 动态注入的运行时系统提示词。
     * @param memoryQuestion 需要写入会话记忆的问题文本。
     * @return Agent 最终回答。
     */
    public String callInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        PreparedMessages prepared = prepareMessages(question, runtimeSystemPrompt, memoryQuestion, conversationId);
        List<Message> messages = prepared.messages();
        boolean useMemory = prepared.useMemory();

        int round = 0;

        while (true) {
            round++;
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                messages.add(new UserMessage(ReactAgentPrompts.FORCE_FINAL_ANSWER_PROMPT));

                String finalText = chatClient.prompt().messages(messages).call().content();
                if (useMemory) {
                    addAssistantMemory(conversationId, finalText);
                }
                return finalText;
            }

            ChatClientResponse chatResponse = chatClient
                    .prompt()
                    .messages(messages)
                    .call()
                    .chatClientResponse();

            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();

            AssistantMessage.Builder builder = AssistantMessage.builder().content(aiText);

            // ===== 没有工具调用，视为最终答案 =====
            if (!chatResponse.chatResponse().hasToolCalls()) {
                if (useMemory) {
                    addAssistantMemory(conversationId, aiText);
                }
                return aiText;
            }

            // ===== 有工具调用：执行工具 =====
            List<AssistantMessage.ToolCall> normalizedToolCalls = normalizeToolCalls(
                    chatResponse.chatResponse().getResult().getOutput().getToolCalls()
            );
            messages.add(builder.toolCalls(normalizedToolCalls).build());

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : normalizedToolCalls) {
                futures.add(CompletableFuture.runAsync(() -> {
                    boolean acquired = false;
                    try {
                        toolSemaphore.acquire();
                        acquired = true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        addErrorToolResponse(messages, toolCall, "工具执行被中断");
                        return;
                    }
                    try {
                        String toolName = toolCall.name();
                        String argsJson = toolCall.arguments();

                        ToolCallback callback = findTool(toolName);
                        if (callback == null) {
                            addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName);
                            return;
                        }
                        NormalizationResult normalizationResult = JsonArgumentUtils.normalizeJsonArguments(argsJson);
                        if (!normalizationResult.valid()) {
                            addErrorToolResponse(messages, toolCall, "工具参数不是合法 JSON：" + normalizationResult.errorMessage());
                            return;
                        }

                        Object result;
                        try (AgentRuntimeContext.Scope ignored = AgentRuntimeContext.open(userId, conversationId)) {
                            result = callback.call(normalizationResult.normalizedJson());
                            ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result.toString());
                            messages.add(ToolResponseMessage.builder().responses(List.of(tr)).build());
                        }
                    } catch (Exception ex) {
                        addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage());
                    } finally {
                        if (acquired) {
                            toolSemaphore.release();
                        }
                    }
                }, toolExecutor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("并发工具调用等待异常。conversationId={}, message={}", conversationId, e.getMessage());
            }
        }
    }


    /**
     * 每轮执行的状态标记位
     */
    private static class RoundState {
        boolean hasToolCalls;

        StringBuilder textBuffer = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new CopyOnWriteArrayList<>();
    }


    /**
     * 流式处理 `stream` 对应内容。
     *
     * @param question question 参数。
     * @return 返回处理结果。
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return streamInternal(null, null, question, null, question);
    }

    // 带会话记忆
    /**
     * 流式处理 `stream` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param question question 参数。
     * @return 返回处理结果。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question) {
        return streamInternal(userId, conversationId, question, null, question);
    }

    /**
     * 流式处理 `stream` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param question question 参数。
     * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
     * @return 返回处理结果。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    /**
     * 流式处理 `stream` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param question question 参数。
     * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
     * @param memoryQuestion memoryQuestion 参数。
     * @return 返回处理结果。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    /**
     * 流式处理 `stream Internal` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param question question 参数。
     * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
     * @param memoryQuestion memoryQuestion 参数。
     * @return 返回处理结果。
     */
    public Flux<AgentStreamEvent> streamInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        log.info("开始执行快速模式流式回答，conversationId={}, questionLength={}",
                conversationId,
                question == null ? 0 : question.length());

        PreparedMessages prepared = prepareMessages(question, runtimeSystemPrompt, memoryQuestion, conversationId);
        List<Message> messages = prepared.messages();
        boolean useMemory = prepared.useMemory();

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 迭代轮次
        AtomicLong roundCounter = new AtomicLong(0);
        // 是否发送最终结果标记位
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);

        hasSentFinalResult.set(false);
        roundCounter.set(0);

        // 收集最终答案，存储memory
        StringBuilder finalAnswerBuffer = new StringBuilder();

        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, userId);

        return sink.asFlux()
                .doOnCancel(() -> hasSentFinalResult.set(true))
                .doFinally(signalType -> {
                    log.info("快速模式流式回答结束，conversationId={}, rounds={}, finalAnswerLength={}",
                            conversationId,
                            roundCounter.get(),
                            finalAnswerBuffer.length());
                });
    }

    /**
     * 处理 `schedule Round` 对应逻辑。
     *
     * @param messages messages 参数。
     * @param sink sink 参数。
     * @param roundCounter roundCounter 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     * @param finalAnswerBuffer finalAnswerBuffer 参数。
     * @param useMemory useMemory 参数。
     * @param conversationId conversationId 参数。
     * @param userId userId 参数。
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId, Long userId) {
        // 轮次+1
        roundCounter.incrementAndGet();
        log.info("快速模式开始第 {} 轮处理，conversationId={}",
                roundCounter.get(),
                conversationId);
        RoundState state = new RoundState();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .doOnNext(chunk -> processChunk(chunk, sink, state, conversationId))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, userId))
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
    }

    /**
     * 处理 `process Chunk` 对应逻辑。
     *
     * @param chunk chunk 参数。
     * @param sink sink 参数。
     * @param state state 参数。
     * @param conversationId conversationId 参数。
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink, RoundState state, String conversationId) {

        if (chunk == null || chunk.getResult() == null ||
                chunk.getResult().getOutput() == null) return;

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (tc != null && !tc.isEmpty()) {
            state.hasToolCalls = true;

            for (AssistantMessage.ToolCall incoming : tc) {
                mergeToolCall(state, incoming);
            }
            return;
        }

        // 还没出现 tool_call，发送并缓存文本
        if (text != null) {
            sink.tryEmitNext(AgentStreamEventFactory.assistantDelta(conversationId, text));
            state.textBuffer.append(text);
        }
    }

    /**
     * 处理 `merge Tool Call` 对应逻辑。
     *
     * @param state state 参数。
     * @param incoming incoming 参数。
     */
    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        if (incoming == null) {
            return;
        }

        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (existing == null) {
                continue;
            }

            String existingId = existing.id();
            String incomingId = incoming.id();
            if (existingId != null && incomingId != null && existingId.equals(incomingId)) {

                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");

                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(existing.id(), TOOL_CALL_TYPE_FUNCTION, existing.name(), mergedArgs)
                );
                return;
            }
        }

        // 新 tool call
        state.toolCalls.add(incoming);
    }


    /**
     * 轮次结束处理工具调用
     */
    private void finishRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, RoundState state, AtomicLong roundCounter,
                             AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer, boolean useMemory,
                             String conversationId, Long userId) {

        // 如果整轮都没有 tool_call，才是最终答案
        if (!state.hasToolCalls) {
            String finalText = state.textBuffer.toString();
            finalAnswerBuffer.setLength(0);
            finalAnswerBuffer.append(finalText);
            log.info("快速模式第 {} 轮直接产出最终答案，conversationId={}, answerLength={}",
                    roundCounter.get(),
                    conversationId,
                    finalText.length());
            sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(conversationId, finalText));
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);

            if (useMemory) {
                virtualThreadExecutor.execute(() -> addAssistantMemory(conversationId, finalText));
            }
            return;
        }

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            log.warn("快速模式达到最大轮次限制，开始强制总结。conversationId={}, round={}",
                    conversationId,
                    roundCounter.get());
            forceFinalStream(conversationId, useMemory, messages, sink, hasSentFinalResult);
            return;
        }

        // TOOL_CALL
        List<AssistantMessage.ToolCall> normalizedToolCalls = normalizeToolCalls(state.toolCalls);
        log.info("快速模式第 {} 轮识别到工具调用，conversationId={}, toolCallCount={}, tools={}",
                roundCounter.get(),
                conversationId,
                normalizedToolCalls.size(),
                normalizedToolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(normalizedToolCalls).build();

        messages.add(assistantMsg);

        executeToolCalls(normalizedToolCalls, messages, hasSentFinalResult, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, userId);
            }
        }, userId, conversationId);
    }


    /**
     * 处理 `force Final Stream` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param useMemory useMemory 参数。
     * @param messages messages 参数。
     * @param sink sink 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     */
    private void forceFinalStream(String conversationId, boolean useMemory, List<Message> messages, Sinks.Many<AgentStreamEvent> sink, AtomicBoolean hasSentFinalResult) {
        messages.add(new UserMessage(ReactAgentPrompts.FORCE_FINAL_ANSWER_PROMPT));

        StringBuilder stringBuilder = new StringBuilder();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }

                    String text = chunk.getResult()
                            .getOutput()
                            .getText();

                    if (text != null && !hasSentFinalResult.get()) {
                        sink.tryEmitNext(AgentStreamEventFactory.assistantDelta(conversationId, text));
                        stringBuilder.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String finalText = stringBuilder.toString();
                    sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(conversationId, finalText));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    if (useMemory) {
                        virtualThreadExecutor.execute(() -> addAssistantMemory(conversationId, finalText));
                    }
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    /**
     * 执行 `execute Tool Calls` 对应逻辑。
     *
     * @param toolCalls toolCalls 参数。
     * @param messages messages 参数。
     * @param hasSentFinalResult hasSentFinalResult 参数。
     * @param onComplete onComplete 参数。
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                  List<Message> messages,
                                  AtomicBoolean hasSentFinalResult,
                                  Runnable onComplete,
                                  Long userId,
                                  String conversationId) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            toolExecutor.execute(() -> {
                boolean acquired = false;
                try {
                    if (hasSentFinalResult.get()) {
                        return;
                    }

                    toolSemaphore.acquire();
                    acquired = true;

                    String toolName = tc.name();
                    String argsJson = tc.arguments();

                    ToolCallback callback = findTool(toolName);
                    if (callback == null) {
                        addErrorToolResponse(messages, tc, "工具未找到：" + toolName);
                        return;
                    }
                    NormalizationResult normalizationResult = JsonArgumentUtils.normalizeJsonArguments(argsJson);
                    if (!normalizationResult.valid()) {
                        addErrorToolResponse(messages, tc, "工具参数不是合法 JSON：" + normalizationResult.errorMessage());
                        return;
                    }

                    try (AgentRuntimeContext.Scope ignored = AgentRuntimeContext.open(userId, conversationId)) {
                        Object result = callback.call(normalizationResult.normalizedJson());
                        String resultStr = Objects.toString(result, "");
                        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                                tc.id(), toolName, resultStr);
                        messages.add(ToolResponseMessage.builder()
                                .responses(List.of(tr))
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    addErrorToolResponse(messages, tc, "工具执行被中断");
                } catch (Exception ex) {
                    addErrorToolResponse(messages, tc, "工具执行失败：" + ex.getMessage());
                } finally {
                    if (acquired) {
                        toolSemaphore.release();
                    }
                    completeToolCall(completedCount, totalToolCalls, onComplete, virtualThreadExecutor);
                }
            });
        }
    }

    /**
     * 处理 `complete Tool Call` 对应逻辑。
     *
     * @param completedCount completedCount 参数。
     * @param total total 参数。
     * @param onComplete onComplete 参数。
     */
    private void completeToolCall(AtomicInteger completedCount, int total, Runnable onComplete, Executor executor) {
        int current = completedCount.incrementAndGet();
        if (current >= total) {
            executor.execute(onComplete);
        }
    }

    /**
     * 处理 `add Error Tool Response` 对应逻辑。
     *
     * @param messages messages 参数。
     * @param toolCall toolCall 参数。
     * @param errMsg errMsg 参数。
     */
    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        String errorJson;
        try {
            errorJson = "{\"error\":" + JsonUtil.objectMapper().writeValueAsString(errMsg) + "}";
        } catch (Exception ex) {
            errorJson = "{\"error\":\"tool execution failed\"}";
        }
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                errorJson
        );

        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    /**
     * 处理 `normalize Tool Calls` 对应逻辑。
     *
     * @param toolCalls toolCalls 参数。
     * @return 返回处理结果。
     */
    private List<AssistantMessage.ToolCall> normalizeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> normalized = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            normalized.add(new AssistantMessage.ToolCall(
                    toolCall.id(),
                    TOOL_CALL_TYPE_FUNCTION,
                    toolCall.name(),
                    normalizeArguments(toolCall)
            ));
        }
        return normalized;
    }

    /**
     * 处理 `normalize Arguments` 对应逻辑。
     *
     * @param toolCall toolCall 参数。
     * @return 返回处理结果。
     */
    private String normalizeArguments(AssistantMessage.ToolCall toolCall) {
        String rawArguments = toolCall == null ? null : toolCall.arguments();
        NormalizationResult result = JsonArgumentUtils.normalizeJsonArguments(rawArguments);
        if (!result.valid()) {
            log.warn("工具调用参数不是合法 JSON，toolName={}, toolId={}, rawArguments={}, error={}",
                    toolCall == null ? null : toolCall.name(),
                    toolCall == null ? null : toolCall.id(),
                    rawArguments,
                    result.errorMessage());
            return rawArguments;
        }
        return result.normalizedJson();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 `Builder` 实例。
     */
    /**
     * SimpleReactAgent 构建器。
     */
    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<Advisor> advisors = new ArrayList<>();
        private String systemPrompt = "";
        private int maxRounds;
        private int toolConcurrency = 3;
        private ChatMemory chatMemory;
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
         * 处理 `name` 对应逻辑。
         *
         * @param name name 参数。
         * @return 返回处理结果。
         */
        public Builder name(String name) {
            this.name = name;
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
        public Builder tools(ToolCallback... tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(tools));
            return this;
        }

        /**
         * 处理 `tools` 对应逻辑。
         *
         * @param tools tools 参数。
         * @return 返回处理结果。
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
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
         * 处理 `system Prompt` 对应逻辑。
         *
         * @param systemPrompt systemPrompt 参数。
         * @return 返回处理结果。
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
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

        public Builder toolConcurrency(int toolConcurrency) {
            this.toolConcurrency = toolConcurrency;
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
         * 构建 `build` 对应结果。
         *
         * @return 返回处理结果。
         */
        public SimpleReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            if (toolExecutor == null) {
                throw new IllegalArgumentException("toolExecutor 不能为空！");
            }
            if (virtualThreadExecutor == null) {
                throw new IllegalArgumentException("virtualThreadExecutor 不能为空！");
            }
            return new SimpleReactAgent(name, chatModel, tools, advisors, systemPrompt, maxRounds, chatMemory, toolConcurrency,
                    toolExecutor, virtualThreadExecutor);
        }
    }

}
