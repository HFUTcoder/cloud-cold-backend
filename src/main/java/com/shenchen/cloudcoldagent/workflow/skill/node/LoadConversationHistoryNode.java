package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * `LoadConversationHistoryNode` 类型实现。
 */
@Component
public class LoadConversationHistoryNode {

    private static final int HISTORY_WINDOW_SIZE = 20;

    private final ChatMemoryRepository chatMemoryRepository;

    public LoadConversationHistoryNode(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String conversationId = state.value(SkillWorkflowStateKeys.CONVERSATION_ID, String.class).orElse(null);
        if (conversationId == null || conversationId.isBlank()) {
            return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.CONVERSATION_HISTORY, List.of()));
        }

        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history == null || history.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.CONVERSATION_HISTORY, List.of()));
        }

        int fromIndex = Math.max(0, history.size() - HISTORY_WINDOW_SIZE);
        List<Message> window = List.copyOf(history.subList(fromIndex, history.size()));
        return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.CONVERSATION_HISTORY, window));
    }
}
