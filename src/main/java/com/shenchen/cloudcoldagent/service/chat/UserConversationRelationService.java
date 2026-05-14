package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.agent.UserConversationRelation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `UserConversationRelationService` 接口定义。
 */
public interface UserConversationRelationService extends IService<UserConversationRelation> {

    List<String> listConversationIdsByUserId(Long userId);

    Long getUserIdByConversationId(String conversationId);

    boolean isConversationOwnedByUser(Long userId, String conversationId);

    void bindUserConversation(Long userId, String conversationId, LocalDateTime now);

    boolean deleteByConversationId(String conversationId);
}
