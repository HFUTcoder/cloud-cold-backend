package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

public record HitlResumeRequest(
        String interruptId,
        ChatModel chatModel,
        List<ToolCallback> tools,
        List<Advisor> advisors,
        int maxRounds,
        Set<String> interceptToolNames,
        Set<String> approvedToolNames
) {
}
