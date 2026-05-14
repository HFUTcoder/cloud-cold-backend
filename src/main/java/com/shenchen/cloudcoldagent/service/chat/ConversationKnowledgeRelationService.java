package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.agent.ConversationKnowledgeRelation;

import java.time.LocalDateTime;

/**
 * `ConversationKnowledgeRelationService` 接口定义。
 */
public interface ConversationKnowledgeRelationService extends IService<ConversationKnowledgeRelation> {

    ConversationKnowledgeRelation getByUserIdAndConversationId(Long userId, String conversationId);

    void bindKnowledge(Long userId, String conversationId, Long knowledgeId, LocalDateTime now);

    boolean unbindKnowledge(Long userId, String conversationId);

    boolean deleteByConversationId(String conversationId);
}
