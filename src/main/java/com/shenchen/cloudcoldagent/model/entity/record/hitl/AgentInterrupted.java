package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * 创建 `AgentInterrupted` 实例。
 *
 * @param pendingToolCalls pendingToolCalls 参数。
 * @param checkpointMessages checkpointMessages 参数。
 * @param context context 参数。
 */
/**
 * `AgentInterrupted` 记录对象。
 */
public record AgentInterrupted(List<PendingToolCall> pendingToolCalls,
                               List<Message> checkpointMessages,
                               Map<String, Object> context) {
}
