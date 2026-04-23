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

@Component
public class LoadBoundSkillsNode {

    private final ChatConversationService chatConversationService;

    public LoadBoundSkillsNode(ChatConversationService chatConversationService) {
        this.chatConversationService = chatConversationService;
    }

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
