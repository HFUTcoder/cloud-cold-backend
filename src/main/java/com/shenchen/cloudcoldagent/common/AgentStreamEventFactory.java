package com.shenchen.cloudcoldagent.common;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一封装 AgentStreamEvent 的构造。
 */
public final class AgentStreamEventFactory {

    /**
     * 创建 `AgentStreamEventFactory` 实例。
     */
    private AgentStreamEventFactory() {
    }

    /**
     * 处理 `assistant Delta` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent assistantDelta(String conversationId, String content) {
        return build("assistant_delta", conversationId, null, AgentPayload.text(content));
    }

    /**
     * 处理 `final Answer` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent finalAnswer(String conversationId, String content) {
        return build("final_answer", conversationId, null, AgentPayload.finalAnswer(content));
    }

    /**
     * 处理 `error` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param interruptId interruptId 参数。
     * @param message message 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent error(String conversationId, String interruptId, String message) {
        return build("error", conversationId, interruptId, AgentPayload.error(message));
    }

    /**
     * 处理 `thinking Step` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param stage stage 参数。
     * @param title title 参数。
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent thinkingStep(String conversationId, String stage, String title, String content) {
        return build("thinking_step", conversationId, null, AgentPayload.thinking(stage, title, content));
    }

    /**
     * 处理 `hitl Interrupt` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param interruptId interruptId 参数。
     * @param agentType agentType 参数。
     * @param status status 参数。
     * @param pendingToolCalls pendingToolCalls 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent hitlInterrupt(String conversationId,
                                                 String interruptId,
                                                 String agentType,
                                                 String status,
                                                 List<PendingToolCall> pendingToolCalls) {
        Map<String, Object> interruptData = new LinkedHashMap<>();
        interruptData.put("agentType", agentType);
        interruptData.put("pendingToolCalls", pendingToolCalls);
        interruptData.put("status", status);
        return build("hitl_interrupt", conversationId, interruptId, AgentPayload.actionRequired(interruptData));
    }

    /**
     * 处理 `empty Hitl Interrupt` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent emptyHitlInterrupt(String conversationId) {
        return build("hitl_interrupt", conversationId, null, AgentPayload.actionRequired(Map.of()));
    }

    /**
     * 处理 `knowledge Retrieval` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param images images 参数。
     * @return 返回处理结果。
     */
    public static AgentStreamEvent knowledgeRetrieval(String conversationId, List<RetrievedKnowledgeImage> images) {
        Map<String, Object> retrievalData = new LinkedHashMap<>();
        retrievalData.put("images", images == null ? List.of() : images);
        retrievalData.put("count", images == null ? 0 : images.size());
        return build("knowledge_retrieval", conversationId, null, AgentPayload.actionRequired(retrievalData));
    }

    /**
     * 构建 `build` 对应结果。
     *
     * @param eventType eventType 参数。
     * @param conversationId conversationId 参数。
     * @param interruptId interruptId 参数。
     * @param payload payload 参数。
     * @return 返回处理结果。
     */
    private static AgentStreamEvent build(String eventType,
                                          String conversationId,
                                          String interruptId,
                                          AgentPayload payload) {
        AgentStreamEvent.AgentStreamEventBuilder builder = AgentStreamEvent.builder()
                .type(eventType)
                .data(payload == null ? Map.of() : payload.toEventData());
        if (conversationId != null && !conversationId.isBlank()) {
            builder.conversationId(conversationId);
        }
        if (interruptId != null && !interruptId.isBlank()) {
            builder.interruptId(interruptId);
        }
        return builder.build();
    }
}
