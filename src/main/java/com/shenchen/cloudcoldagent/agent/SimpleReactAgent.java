package com.shenchen.cloudcoldagent.agent;

import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.ReactAgentPrompts;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class SimpleReactAgent extends BaseAgent {

    private final String REACT_AGENT_SYSTEM_PROMPT = ReactAgentPrompts.STRICT_REACT_SYSTEM_PROMPT;
    private final String name;
    private final String systemPrompt;
    private ChatClient chatClient;

    public SimpleReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, List<Advisor> advisors,
                            String systemPrompt, int maxRounds, ChatMemory chatMemory) {
        super(chatModel, tools, advisors, maxRounds, chatMemory);
        this.name = name;
        this.systemPrompt = systemPrompt;

        initChatClient();

        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

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
     * 非流式输出
     *
     * @param question
     * @return
     */
    public String call(String question) {
        return callInternal(null, null, question, null, question);
    }

    // 带会话记忆
    public String call(Long userId, String conversationId, String question) {
        return callInternal(userId, conversationId, question, null, question);
    }

    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    public String call(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return callInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    public String callInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = useMemory(conversationId);

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));
        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            messages.add(new SystemMessage(runtimeSystemPrompt));
        }

        // ===== 加载历史记忆 =====
        if (useMemory) {
            List<Message> history = getMemoryMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
        }

        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            addUserMemory(conversationId, memoryQuestion == null ? question : memoryQuestion);
        }

        int round = 0;

        while (true) {
            round++;
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                messages.add(new UserMessage(
                        "你已达到最大推理轮次限制。\n" +
                                "请基于当前已有的上下文信息，\n" +
                                "直接给出最终答案。\n" +
                                "禁止再调用任何工具。\n" +
                                "如果信息不完整，请合理总结和说明。"
                ));

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

            normalizedToolCalls.forEach(toolCall -> {
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
                        } catch (Exception ex) {
                            addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage());
                        }
                    });
        }
    }


    /**
     * 运行模式：未知、最终答案、工具调用
     */
    private enum RoundMode {
        UNKNOWN,
        FINAL_ANSWER,
        TOOL_CALL
    }

    /**
     * 每轮执行的状态标记位
     */
    private static class RoundState {
        RoundMode mode = RoundMode.UNKNOWN;

        StringBuilder textBuffer = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }


    /**
     * 流式输出
     *
     * @param question
     * @return
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return streamInternal(null, null, question, null, question);
    }

    // 带会话记忆
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question) {
        return streamInternal(userId, conversationId, question, null, question);
    }

    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, question);
    }

    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        return streamInternal(userId, conversationId, question, runtimeSystemPrompt, memoryQuestion);
    }

    public Flux<AgentStreamEvent> streamInternal(Long userId, String conversationId, String question, String runtimeSystemPrompt, String memoryQuestion) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = useMemory(conversationId);
        log.info("开始执行快速模式流式回答，conversationId={}, questionLength={}, useMemory={}",
                conversationId,
                question == null ? 0 : question.length(),
                useMemory);

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));
        if (runtimeSystemPrompt != null && !runtimeSystemPrompt.isBlank()) {
            messages.add(new SystemMessage(runtimeSystemPrompt));
        }

        // ===== 加载历史记忆 =====
        if (useMemory) {
            List<Message> history = getMemoryMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
        }

        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            addUserMemory(conversationId, memoryQuestion == null ? question : memoryQuestion);
        }

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
                .publishOn(Schedulers.boundedElastic())
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

    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink, RoundState state, String conversationId) {

        if (chunk == null || chunk.getResult() == null ||
                chunk.getResult().getOutput() == null) return;

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;

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
                        new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs)
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
        if (state.mode != RoundMode.TOOL_CALL) {
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
                addAssistantMemory(conversationId, finalText);
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


    private void forceFinalStream(String conversationId, boolean useMemory, List<Message> messages, Sinks.Many<AgentStreamEvent> sink, AtomicBoolean hasSentFinalResult) {
        messages.add(new UserMessage(
                "你已达到最大推理轮次限制。\n" +
                        "请基于当前已有的上下文信息，\n" +
                        "直接给出最终答案。\n" +
                        "禁止再调用任何工具。\n" +
                        "如果信息不完整，请合理总结和说明。"
        ));

        StringBuilder stringBuilder = new StringBuilder();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
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
                    sink.tryEmitNext(AgentStreamEventFactory.finalAnswer(conversationId, stringBuilder.toString()));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    if (useMemory) {
                        addAssistantMemory(conversationId, stringBuilder.toString());
                    }
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                  List<Message> messages,
                                  AtomicBoolean hasSentFinalResult,
                                  Runnable onComplete,
                                  Long userId,
                                  String conversationId) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                String toolName = tc.name();
                String argsJson = tc.arguments();

                ToolCallback callback = findTool(toolName);
                if (callback == null) {
                    addErrorToolResponse(messages, tc, "工具未找到：" + toolName);
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }
                NormalizationResult normalizationResult = JsonArgumentUtils.normalizeJsonArguments(argsJson);
                if (!normalizationResult.valid()) {
                    addErrorToolResponse(messages, tc, "工具参数不是合法 JSON：" + normalizationResult.errorMessage());
                    completeToolCall(completedCount, totalToolCalls, onComplete);
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
                } catch (Exception ex) {
                    addErrorToolResponse(messages, tc, "工具执行失败：" + ex.getMessage());
                } finally {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                }
            });
        }
    }

    private void completeToolCall(AtomicInteger completedCount, int total, Runnable onComplete) {
        int current = completedCount.incrementAndGet();
        if (current >= total) {
            onComplete.run();
        }
    }

    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );

        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

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
                    "function",
                    toolCall.name(),
                    normalizeArguments(toolCall)
            ));
        }
        return normalized;
    }

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

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<Advisor> advisors = new ArrayList<>();
        private String systemPrompt = "";
        private int maxRounds;
        private ChatMemory chatMemory;

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(tools));
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(advisors));
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(advisors);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public SimpleReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            return new SimpleReactAgent(name, chatModel, tools, advisors, systemPrompt, maxRounds, chatMemory);
        }
    }

}
