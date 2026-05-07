package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * 创建 `HitlResumeRequest` 实例。
 *
 * @param interruptId interruptId 参数。
 * @param userId userId 参数。
 * @param conversationId conversationId 参数。
 * @param chatModel chatModel 参数。
 * @param tools tools 参数。
 * @param advisors advisors 参数。
 * @param maxRounds maxRounds 参数。
 * @param interceptToolNames interceptToolNames 参数。
 * @param approvedToolCallIds approvedToolCallIds 参数。
 */
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
