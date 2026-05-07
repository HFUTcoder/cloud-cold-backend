package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * `LoadBoundSkillsNode` 类型实现。
 */
@Component
public class LoadBoundSkillsNode {

    private final ChatConversationService chatConversationService;

    /**
     * 创建 `LoadBoundSkillsNode` 实例。
     *
     * @param chatConversationService chatConversationService 参数。
     */
    public LoadBoundSkillsNode(ChatConversationService chatConversationService) {
        this.chatConversationService = chatConversationService;
    }

    /**
     * 处理 `apply` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        Long userId = state.value(SkillWorkflowStateKeys.USER_ID, Long.class).orElse(null);
        String conversationId = state.value(SkillWorkflowStateKeys.CONVERSATION_ID, String.class).orElse(null);

        List<String> boundSkills = new ArrayList<>();
        if (userId != null && conversationId != null && !conversationId.isBlank()) {
            ChatConversation conversation = chatConversationService.getByConversationId(userId, conversationId);
            if (conversation.getSelectedSkillList() != null) {
                boundSkills.addAll(new LinkedHashSet<>(conversation.getSelectedSkillList()));
            }
        }
        return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.BOUND_SKILLS, boundSkills));
    }
}
