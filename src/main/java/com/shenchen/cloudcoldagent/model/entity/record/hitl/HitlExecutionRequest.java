package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

public record HitlExecutionRequest(
        String conversationId,
        String agentType,
        ChatModel chatModel,
        List<ToolCallback> tools,
        List<Advisor> advisors,
        String executionInput,
        String runtimeSystemPrompt,
        String systemPrompt,
        int maxRounds,
        Set<String> interceptToolNames
) {
}
