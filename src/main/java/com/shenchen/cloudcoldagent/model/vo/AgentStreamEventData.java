package com.shenchen.cloudcoldagent.model.vo;

import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;

import java.io.Serializable;
import java.util.List;

/**
 * Agent 流式事件数据载体，每种事件类型对应一个 record。
 * 替代原 AgentPayload 的弱类型 Map 结构，提供编译期类型安全。
 */
public sealed interface AgentStreamEventData extends Serializable
        permits AgentStreamEventData.AssistantDelta,
                AgentStreamEventData.FinalAnswer,
                AgentStreamEventData.ThinkingStep,
                AgentStreamEventData.Error,
                AgentStreamEventData.HitlInterrupt,
                AgentStreamEventData.KnowledgeRetrieval {

    /**
     * 助手增量文本（assistant_delta / final_answer 共用 content 结构）。
     */
    record AssistantDelta(String content) implements AgentStreamEventData {
    }

    /**
     * 助手最终回答。
     */
    record FinalAnswer(String content) implements AgentStreamEventData {
    }

    /**
     * Agent 思考步骤。
     */
    record ThinkingStep(String stage, String title, String content) implements AgentStreamEventData {
    }

    /**
     * 知识库命中图片。
     */
    record KnowledgeRetrieval(List<RetrievedKnowledgeImage> images, int count) implements AgentStreamEventData {
    }

    /**
     * HITL 人工审批中断。
     */
    record HitlInterrupt(String agentType, List<PendingToolCall> pendingToolCalls, String status)
            implements AgentStreamEventData {
    }

    /**
     * 错误事件，code 对齐 {@link ErrorCode} 枚举。
     */
    record Error(int code, String message, String detail) implements AgentStreamEventData {

        public static Error of(ErrorCode errorCode, String message, String detail) {
            return new Error(errorCode.getCode(),
                    message != null ? message : errorCode.getMessage(),
                    detail);
        }

        public static Error of(ErrorCode errorCode, String message) {
            return of(errorCode, message, null);
        }
    }
}
