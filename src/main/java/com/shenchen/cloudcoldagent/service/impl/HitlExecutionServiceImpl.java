package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.hitl.HITLAdvisor;
import com.shenchen.cloudcoldagent.hitl.HITLState;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlExecutionRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlExecutionResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HitlExecutionServiceImpl implements HitlExecutionService {

    private final HitlCheckpointService hitlCheckpointService;

    public HitlExecutionServiceImpl(HitlCheckpointService hitlCheckpointService) {
        this.hitlCheckpointService = hitlCheckpointService;
    }

    @Override
    public boolean isEnabled(String conversationId, Set<String> interceptToolNames) {
        return conversationId != null
                && !conversationId.isBlank()
                && interceptToolNames != null
                && !interceptToolNames.isEmpty();
    }

    @Override
    public HitlExecutionResult execute(HitlExecutionRequest request) {
        Set<String> interceptToolNames = request.interceptToolNames() == null
                ? Set.of()
                : new LinkedHashSet<>(request.interceptToolNames());
        List<Advisor> runtimeAdvisors = new ArrayList<>(request.advisors() == null ? List.of() : request.advisors());
        runtimeAdvisors.add(new HITLAdvisor(interceptToolNames));

        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(request.tools())
                .internalToolExecutionEnabled(false)
                .build();
        ChatClient chatClient = ChatClient.builder(request.chatModel())
                .defaultOptions(toolOptions)
                .defaultToolCallbacks(request.tools())
                .defaultAdvisors(runtimeAdvisors)
                .build();

        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        if (request.runtimeSystemPrompt() != null && !request.runtimeSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.runtimeSystemPrompt()));
        }
        messages.add(new UserMessage(request.executionInput()));

        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put(HITLAdvisor.HITL_STATE_KEY, new HITLState());

        for (int round = 1; request.maxRounds() <= 0 || round <= request.maxRounds(); round++) {
            ChatClientResponse response = chatClient.prompt().messages(messages).call().chatClientResponse();

            if (Boolean.TRUE.equals(response.context().get(HITLAdvisor.HITL_REQUIRED))) {
                List<AssistantMessage.ToolCall> toolCalls = response.chatResponse().getResult().getOutput().getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());
                }
                executeNonInterceptTools(messages, response, request.tools());
                List<PendingToolCall> pendingToolCalls = castPendingToolCalls(response.context().get(HITLAdvisor.HITL_PENDING_TOOLS));
                HitlCheckpointVO checkpoint = hitlCheckpointService.createCheckpoint(
                        request.conversationId(),
                        request.agentType(),
                        pendingToolCalls,
                        List.copyOf(messages),
                        context
                );
                return new HitlExecutionResult(true, null, "Task execution interrupted by HITL", checkpoint);
            }

            String text = response.chatResponse().getResult().getOutput().getText();
            if (!response.chatResponse().hasToolCalls()) {
                return new HitlExecutionResult(false, text, null, null);
            }

            List<AssistantMessage.ToolCall> toolCalls = response.chatResponse().getResult().getOutput().getToolCalls();
            messages.add(AssistantMessage.builder().content(text).toolCalls(toolCalls).build());
            executeToolCalls(messages, toolCalls, request.tools());
        }
        return new HitlExecutionResult(false, chatClient.prompt().messages(messages).call().content(), null, null);
    }

    @SuppressWarnings("unchecked")
    private List<PendingToolCall> castPendingToolCalls(Object value) {
        if (value instanceof List<?> list) {
            return (List<PendingToolCall>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void executeNonInterceptTools(List<Message> messages, ChatClientResponse response, List<ToolCallback> tools) {
        Object value = response.context().get(HITLAdvisor.HITL_NON_INTERCEPT_TOOLS);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        executeToolCalls(messages, (List<AssistantMessage.ToolCall>) list, tools);
    }

    private void executeToolCalls(List<Message> messages, List<AssistantMessage.ToolCall> toolCalls, List<ToolCallback> tools) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            ToolCallback tool = findTool(tools, toolCall.name());
            String result;
            if (tool == null) {
                result = "工具未找到：" + toolCall.name();
            } else {
                result = String.valueOf(tool.call(toolCall.arguments()));
            }
            messages.add(ToolResponseMessage.builder().responses(
                    List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result))
            ).build());
        }
    }

    private ToolCallback findTool(List<ToolCallback> tools, String toolName) {
        if (tools == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        return tools.stream()
                .filter(tool -> tool.getToolDefinition() != null)
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
    }
}
