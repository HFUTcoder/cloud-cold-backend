package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.HitlResumeService;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 恢复服务实现，负责消费用户审批结果并继续执行被中断的工具调用链。
 */
@Service
@Slf4j
public class HitlResumeServiceImpl implements HitlResumeService {

    private final HitlCheckpointService hitlCheckpointService;

    /**
     * 注入 HITL 恢复所需的 checkpoint 服务。
     *
     * @param hitlCheckpointService HITL checkpoint 服务。
     */
    public HitlResumeServiceImpl(HitlCheckpointService hitlCheckpointService) {
        this.hitlCheckpointService = hitlCheckpointService;
    }

    /**
     * 消费已 resolve 的 checkpoint，并继续执行本次被中断的工具调用。
     *
     * @param request HITL 恢复请求。
     * @return 恢复执行结果，返回的是本次工具恢复后的直接输出。
     */
    @Override
    public HitlResumeResult resume(HitlResumeRequest request) {
        if (request == null || StringUtils.isBlank(request.interruptId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "interruptId 不能为空");
        }
        log.info("开始恢复 HITL 中断执行，interruptId={}", request.interruptId());
        HitlCheckpointVO checkpoint = hitlCheckpointService.consumeResolvedCheckpoint(request.userId(), request.interruptId());
        String conversationId = StringUtils.defaultIfBlank(request.conversationId(), checkpoint.getConversationId());

        AgentInterrupted interrupted = hitlCheckpointService.loadInterrupted(request.interruptId());
        List<Message> messages = new ArrayList<>(interrupted.checkpointMessages() == null
                ? List.of()
                : interrupted.checkpointMessages());
        List<ToolResponseMessage.ToolResponse> resumedResponses = applyFeedbacks(
                messages,
                interrupted.pendingToolCalls(),
                checkpoint.getFeedbacks(),
                request.tools(),
                request.userId(), conversationId);
        log.info("已将 HITL 反馈应用到执行上下文，interruptId={}, conversationId={}, pendingToolCount={}, feedbackCount={}",
                request.interruptId(),
                checkpoint.getConversationId(),
                interrupted.pendingToolCalls() == null ? 0 : interrupted.pendingToolCalls().size(),
                checkpoint.getFeedbacks() == null ? 0 : checkpoint.getFeedbacks().size());
        String mergedContent = resumedResponses.stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .filter(StringUtils::isNotBlank)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        log.info("HITL 恢复工具调用完成，interruptId={}, conversationId={}, resumedToolCount={}, contentLength={}",
                request.interruptId(),
                checkpoint.getConversationId(),
                resumedResponses.size(),
                mergedContent.length());
        return new HitlResumeResult(false, mergedContent, null, null);
    }

    /**
     * 提取本轮反馈中被批准继续执行的 tool call id。
     *
     * @param pendingToolCalls 原始待审批工具调用列表。
     * @param feedbacks 用户反馈列表。
     * @return 被批准的 tool call id 集合。
     */
    private List<ToolResponseMessage.ToolResponse> applyFeedbacks(List<Message> messages,
                                                                  List<PendingToolCall> pendingToolCalls,
                                                                  List<PendingToolCall> feedbacks,
                                                                  List<ToolCallback> tools,
                                                                  Long userId,
                                                                  String conversationId) {
        Map<String, PendingToolCall> feedbackMap = new LinkedHashMap<>();
        if (feedbacks != null) {
            for (PendingToolCall feedback : feedbacks) {
                if (feedback != null && StringUtils.isNotBlank(feedback.id())) {
                    feedbackMap.put(feedback.id(), feedback);
                }
            }
        }
        List<ToolResponseMessage.ToolResponse> resumedResponses = new ArrayList<>();

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
                log.info("HITL resume 合并工具参数，toolName={}, toolId={}, feedbackArgs={}, pendingArgs={}, mergedArgs={}",
                        StringUtils.defaultIfBlank(feedback.name(), pendingToolCall.name()),
                        pendingToolCall.id(),
                        feedback.arguments(),
                        pendingToolCall.arguments(),
                        arguments);
                result = executeTool(
                        StringUtils.defaultIfBlank(feedback.name(), pendingToolCall.name()),
                        pendingToolCall.id(),
                        arguments,
                        tools,
                        userId,
                        conversationId
                );
            }

            ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                    pendingToolCall.id(),
                    pendingToolCall.name(),
                    result
            );
            resumedResponses.add(toolResponse);
            messages.add(ToolResponseMessage.builder().responses(List.of(toolResponse)).build());
        }
        return resumedResponses;
    }

    /**
     * 执行 `execute Tool` 对应逻辑。
     *
     * @param toolName toolName 参数。
     * @param toolId toolId 参数。
     * @param rawArguments rawArguments 参数。
     * @param tools tools 参数。
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    private String executeTool(String toolName,
                               String toolId,
                               String rawArguments,
                               List<ToolCallback> tools,
                               Long userId,
                               String conversationId) {
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
            log.info("HITL resume 执行工具，toolName={}, toolId={}, finalArgs={}", toolName, toolId, arguments);
            try (AgentRuntimeContext.Scope ignored = AgentRuntimeContext.open(userId, conversationId)) {
                return String.valueOf(tool.call(arguments));
            }
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败：" + escapeJson(StringUtils.defaultIfBlank(e.getMessage(), "unknown error")) +
                    "\",\"toolId\":\"" + escapeJson(toolId) + "\"}";
        }
    }

    /**
     * 查找 `find Tool` 对应结果。
     *
     * @param tools tools 参数。
     * @param toolName toolName 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `escape Json` 对应逻辑。
     *
     * @param text text 参数。
     * @return 返回处理结果。
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
