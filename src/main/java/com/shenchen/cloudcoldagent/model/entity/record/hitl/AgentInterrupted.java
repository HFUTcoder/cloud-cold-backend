package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public record AgentInterrupted(List<PendingToolCall> pendingToolCalls,
                               List<Message> checkpointMessages,
                               Map<String, Object> context) {
}
