package com.shenchen.cloudcoldagent.common;

import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEventData;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.List;

/**
 * 统一封装 AgentStreamEvent 的构造。
 */
public final class AgentStreamEventFactory {

    private AgentStreamEventFactory() {
    }

    /**
     * 助手增量文本事件。
     */
    public static AgentStreamEvent assistantDelta(String conversationId, String content) {
        return build("assistant_delta", conversationId, null,
                new AgentStreamEventData.AssistantDelta(content));
    }

    /**
     * 助手最终回答事件。
     */
    public static AgentStreamEvent finalAnswer(String conversationId, String content) {
        return build("final_answer", conversationId, null,
                new AgentStreamEventData.FinalAnswer(content));
    }

    /**
     * 错误事件。
     *
     * @param conversationId 会话 id，可为 null。
     * @param interruptId    HITL 中断 id，可为 null。
     * @param errorCode      错误码枚举。
     * @param message        错误消息。
     */
    public static AgentStreamEvent error(String conversationId, String interruptId,
                                          ErrorCode errorCode, String message) {
        return build("error", conversationId, interruptId,
                AgentStreamEventData.Error.of(errorCode, message));
    }

    /**
     * Agent 思考步骤事件。
     */
    public static AgentStreamEvent thinkingStep(String conversationId, String stage, String title, String content) {
        return build("thinking_step", conversationId, null,
                new AgentStreamEventData.ThinkingStep(stage, title, content));
    }

    /**
     * HITL 人工审批中断事件。
     */
    public static AgentStreamEvent hitlInterrupt(String conversationId,
                                                  String interruptId,
                                                  String agentType,
                                                  String status,
                                                  List<PendingToolCall> pendingToolCalls) {
        return build("hitl_interrupt", conversationId, interruptId,
                new AgentStreamEventData.HitlInterrupt(agentType, pendingToolCalls, status));
    }

    /**
     * 空的 HITL 中断事件（checkpoint 为空时的兜底）。
     */
    public static AgentStreamEvent emptyHitlInterrupt(String conversationId) {
        return build("hitl_interrupt", conversationId, null,
                new AgentStreamEventData.HitlInterrupt(null, List.of(), null));
    }

    /**
     * 知识库命中图片事件。
     */
    public static AgentStreamEvent knowledgeRetrieval(String conversationId, List<RetrievedKnowledgeImage> images) {
        List<RetrievedKnowledgeImage> safeImages = images == null ? List.of() : images;
        return build("knowledge_retrieval", conversationId, null,
                new AgentStreamEventData.KnowledgeRetrieval(safeImages, safeImages.size()));
    }

    private static AgentStreamEvent build(String eventType,
                                          String conversationId,
                                          String interruptId,
                                          AgentStreamEventData data) {
        AgentStreamEvent.AgentStreamEventBuilder builder = AgentStreamEvent.builder()
                .type(eventType)
                .data(data);
        if (conversationId != null && !conversationId.isBlank()) {
            builder.conversationId(conversationId);
        }
        if (interruptId != null && !interruptId.isBlank()) {
            builder.interruptId(interruptId);
        }
        return builder.build();
    }
}
