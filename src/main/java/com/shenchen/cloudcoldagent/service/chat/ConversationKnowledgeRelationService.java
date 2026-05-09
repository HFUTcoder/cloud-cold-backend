package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ConversationKnowledgeRelation;

import java.time.LocalDateTime;

/**
 * `ConversationKnowledgeRelationService` 接口定义。
 */
public interface ConversationKnowledgeRelationService extends IService<ConversationKnowledgeRelation> {

    /**
     * 获取 `get By User Id And Conversation Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    ConversationKnowledgeRelation getByUserIdAndConversationId(Long userId, String conversationId);

    /**
     * 处理 `bind Knowledge` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param now now 参数。
     */
    void bindKnowledge(Long userId, String conversationId, Long knowledgeId, LocalDateTime now);

    /**
     * 处理 `unbind Knowledge` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean unbindKnowledge(Long userId, String conversationId);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByConversationId(String conversationId);
}
