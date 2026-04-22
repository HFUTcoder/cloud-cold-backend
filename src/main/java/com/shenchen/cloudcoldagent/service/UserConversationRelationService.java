package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.UserConversationRelation;

import java.time.LocalDateTime;
import java.util.List;

public interface UserConversationRelationService extends IService<UserConversationRelation> {

    List<String> listConversationIdsByUserId(Long userId);

    boolean isConversationOwnedByUser(Long userId, String conversationId);

    void bindUserConversation(Long userId, String conversationId, LocalDateTime now);

    boolean deleteByConversationId(String conversationId);
}
