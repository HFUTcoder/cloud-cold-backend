package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * `HitlResumeRequest` 记录对象。
 */
public record HitlResumeRequest(
        String interruptId,
        Long userId,
        String conversationId,
        ChatModel chatModel,
        List<ToolCallback> tools,
        List<Advisor> advisors,
        int maxRounds,
        Set<String> interceptToolNames,
        Set<String> approvedToolCallIds
) {
}
