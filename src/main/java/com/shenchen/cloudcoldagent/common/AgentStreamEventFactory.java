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

    private AgentStreamEventFactory() {
    }

    public static AgentStreamEvent assistantDelta(String conversationId, String content) {
        return build("assistant_delta", conversationId, null, AgentPayload.text(content));
    }

    public static AgentStreamEvent finalAnswer(String conversationId, String content) {
        return build("final_answer", conversationId, null, AgentPayload.finalAnswer(content));
    }

    public static AgentStreamEvent error(String conversationId, String interruptId, String message) {
        return build("error", conversationId, interruptId, AgentPayload.error(message));
    }

    public static AgentStreamEvent thinkingStep(String conversationId, String stage, String title, String content) {
        return build("thinking_step", conversationId, null, AgentPayload.thinking(stage, title, content));
    }

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

    public static AgentStreamEvent emptyHitlInterrupt(String conversationId) {
        return build("hitl_interrupt", conversationId, null, AgentPayload.actionRequired(Map.of()));
    }

    public static AgentStreamEvent knowledgeRetrieval(String conversationId, List<RetrievedKnowledgeImage> images) {
        Map<String, Object> retrievalData = new LinkedHashMap<>();
        retrievalData.put("images", images == null ? List.of() : images);
        retrievalData.put("count", images == null ? 0 : images.size());
        return build("knowledge_retrieval", conversationId, null, AgentPayload.actionRequired(retrievalData));
    }

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
