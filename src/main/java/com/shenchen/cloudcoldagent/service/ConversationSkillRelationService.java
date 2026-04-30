package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ConversationSkillRelation;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationSkillRelationService extends IService<ConversationSkillRelation> {

    List<String> listSkillNamesByUserIdAndConversationId(Long userId, String conversationId);

    void replaceSkills(Long userId, String conversationId, List<String> skillNames, LocalDateTime now);

    boolean deleteByConversationId(String conversationId);
}
