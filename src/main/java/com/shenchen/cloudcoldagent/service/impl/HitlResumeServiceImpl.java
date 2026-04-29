package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.hitl.HITLAdvisor;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HitlResumeServiceImpl implements HitlResumeService {

    private static final String STATUS_RESOLVED = "RESOLVED";

    private final HitlCheckpointService hitlCheckpointService;

    public HitlResumeServiceImpl(HitlCheckpointService hitlCheckpointService) {
        this.hitlCheckpointService = hitlCheckpointService;
    }

    @Override
    public HitlResumeResult resume(HitlResumeRequest request) {
        if (request == null || StringUtils.isBlank(request.interruptId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "interruptId 不能为空");
        }
        HitlCheckpointVO checkpoint = hitlCheckpointService.getByInterruptId(request.interruptId());
        if (!STATUS_RESOLVED.equals(checkpoint.getStatus())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 checkpoint 尚未 resolve，不能 resume");
        }

        AgentInterrupted interrupted = hitlCheckpointService.loadInterrupted(request.interruptId());
        List<Message> messages = new ArrayList<>(interrupted.checkpointMessages() == null
                ? List.of()
                : interrupted.checkpointMessages());
        applyFeedbacks(messages, interrupted.pendingToolCalls(), checkpoint.getFeedbacks(), request.tools());

        Set<String> approvedToolNames = new LinkedHashSet<>(
                request.approvedToolNames() == null ? Set.of() : request.approvedToolNames()
        );
        approvedToolNames.addAll(extractApprovedToolNames(interrupted.pendingToolCalls(), checkpoint.getFeedbacks()));

        Set<String> interceptToolNames = request.interceptToolNames() == null
                ? Set.of()
                : new LinkedHashSet<>(request.interceptToolNames());
        List<Advisor> runtimeAdvisors = new ArrayList<>(request.advisors() == null ? List.of() : request.advisors());
        runtimeAdvisors.add(new HITLAdvisor(interceptToolNames, approvedToolNames));

        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(request.tools())
                .internalToolExecutionEnabled(false)
                .build();
        ChatClient chatClient = ChatClient.builder(request.chatModel())
                .defaultOptions(toolOptions)
                .defaultToolCallbacks(request.tools())
                .defaultAdvisors(runtimeAdvisors)
                .build();

        for (int round = 1; request.maxRounds() <= 0 || round <= request.maxRounds(); round++) {
            ChatClientResponse response = chatClient.prompt().messages(messages).call().chatClientResponse();

            if (Boolean.TRUE.equals(response.context().get(HITLAdvisor.HITL_REQUIRED))) {
                List<AssistantMessage.ToolCall> toolCalls = response.chatResponse().getResult().getOutput().getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());
                }
                executeNonInterceptTools(messages, response, request.tools());
                List<PendingToolCall> pendingToolCalls = castPendingToolCalls(response.context().get(HITLAdvisor.HITL_PENDING_TOOLS));
                Map<String, Object> context = interrupted.context() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(interrupted.context());
                context.put("approvedToolNames", new ArrayList<>(approvedToolNames));
                HitlCheckpointVO nextCheckpoint = hitlCheckpointService.createCheckpoint(
                        checkpoint.getConversationId(),
                        checkpoint.getAgentType(),
                        pendingToolCalls,
                        List.copyOf(messages),
                        context
                );
                return new HitlResumeResult(true, null, "Task execution interrupted by HITL", nextCheckpoint);
            }

            String text = response.chatResponse().getResult().getOutput().getText();
            if (!response.chatResponse().hasToolCalls()) {
                return new HitlResumeResult(false, text, null, null);
            }

            List<AssistantMessage.ToolCall> toolCalls = response.chatResponse().getResult().getOutput().getToolCalls();
            messages.add(AssistantMessage.builder().content(text).toolCalls(toolCalls).build());
            executeToolCalls(messages, toolCalls, request.tools());
        }

        String finalContent = chatClient.prompt().messages(messages).call().content();
        return new HitlResumeResult(false, finalContent, null, null);
    }

    private Set<String> extractApprovedToolNames(List<PendingToolCall> pendingToolCalls,
                                                 List<PendingToolCall> feedbacks) {
        Map<String, PendingToolCall> pendingMap = new LinkedHashMap<>();
        for (PendingToolCall pendingToolCall : pendingToolCalls == null ? List.<PendingToolCall>of() : pendingToolCalls) {
            if (pendingToolCall != null && StringUtils.isNotBlank(pendingToolCall.id())) {
                pendingMap.put(pendingToolCall.id(), pendingToolCall);
            }
        }

        Set<String> toolNames = new LinkedHashSet<>();
        for (PendingToolCall feedback : feedbacks == null ? List.<PendingToolCall>of() : feedbacks) {
            if (feedback == null || StringUtils.isBlank(feedback.id()) || feedback.result() == null) {
                continue;
            }
            if (feedback.result() == PendingToolCall.FeedbackResult.REJECTED) {
                continue;
            }
            PendingToolCall pending = pendingMap.get(feedback.id());
            if (pending == null) {
                continue;
            }
            String toolName = StringUtils.defaultIfBlank(feedback.name(), pending.name());
            if (StringUtils.isNotBlank(toolName)) {
                toolNames.add(toolName);
            }
        }
        return toolNames;
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

    private void applyFeedbacks(List<Message> messages,
                                List<PendingToolCall> pendingToolCalls,
                                List<PendingToolCall> feedbacks,
                                List<ToolCallback> tools) {
        Map<String, PendingToolCall> feedbackMap = new LinkedHashMap<>();
        if (feedbacks != null) {
            for (PendingToolCall feedback : feedbacks) {
                if (feedback != null && StringUtils.isNotBlank(feedback.id())) {
                    feedbackMap.put(feedback.id(), feedback);
                }
            }
        }

        for (PendingToolCall pendingToolCall : pendingToolCalls == null ? List.<PendingToolCall>of() : pendingToolCalls) {
            if (pendingToolCall == null || StringUtils.isBlank(pendingToolCall.id())) {
                continue;
            }
            PendingToolCall feedback = feedbackMap.get(pendingToolCall.id());
            if (feedback == null || feedback.result() == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "存在未处理的待确认工具调用，不能 resume");
            }

            String result;
            if (feedback.result() == PendingToolCall.FeedbackResult.REJECTED) {
                result = "{\"rejected\":true,\"message\":\"%s\"}".formatted(escapeJson(StringUtils.defaultIfBlank(
                        feedback.description(), "用户已拒绝执行该工具"
                )));
            } else {
                String arguments = StringUtils.defaultIfBlank(feedback.arguments(), pendingToolCall.arguments());
                result = executeTool(
                        StringUtils.defaultIfBlank(feedback.name(), pendingToolCall.name()),
                        pendingToolCall.id(),
                        arguments,
                        tools
                );
            }

            messages.add(ToolResponseMessage.builder().responses(
                    List.of(new ToolResponseMessage.ToolResponse(
                            pendingToolCall.id(),
                            pendingToolCall.name(),
                            result
                    ))
            ).build());
        }
    }

    private String executeTool(String toolName, String toolId, String rawArguments, List<ToolCallback> tools) {
        ToolCallback tool = findTool(tools, toolName);
        if (tool == null) {
            return "{\"error\":\"工具未找到：" + escapeJson(toolName) + "\"}";
        }
        NormalizationResult normalizationResult = JsonArgumentUtils.normalizeJsonArguments(rawArguments);
        if (!normalizationResult.valid()) {
            return "{\"error\":\"工具参数不是合法 JSON：" + escapeJson(StringUtils.defaultIfBlank(
                    normalizationResult.errorMessage(), "unknown error")) +
                    "\",\"toolId\":\"" + escapeJson(toolId) + "\"}";
        }
        String arguments = normalizationResult.normalizedJson();
        Map<String, Object> structuredArguments = JsonArgumentUtils.readObjectMap(arguments);
        String validationError = JsonArgumentUtils.validateStructuredToolArguments(toolName, structuredArguments);
        if (StringUtils.isNotBlank(validationError)) {
            return "{\"error\":\"" + escapeJson(validationError) +
                    "\",\"toolId\":\"" + escapeJson(toolId) + "\"}";
        }
        try {
            return String.valueOf(tool.call(arguments));
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败：" + escapeJson(StringUtils.defaultIfBlank(e.getMessage(), "unknown error")) +
                    "\",\"toolId\":\"" + escapeJson(toolId) + "\"}";
        }
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
                NormalizationResult normalizationResult = JsonArgumentUtils.normalizeJsonArguments(toolCall.arguments());
                if (!normalizationResult.valid()) {
                    result = "{\"error\":\"工具参数不是合法 JSON：" + escapeJson(StringUtils.defaultIfBlank(
                            normalizationResult.errorMessage(), "unknown error")) + "\"}";
                } else {
                    result = String.valueOf(tool.call(normalizationResult.normalizedJson()));
                }
            }
            messages.add(ToolResponseMessage.builder().responses(
                    List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result))
            ).build());
        }
    }

    private ToolCallback findTool(List<ToolCallback> tools, String toolName) {
        if (tools == null || StringUtils.isBlank(toolName)) {
            return null;
        }
        return tools.stream()
                .filter(tool -> tool.getToolDefinition() != null)
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
