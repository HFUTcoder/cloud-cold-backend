package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.agent.ConversationSkillRelation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `ConversationSkillRelationService` 接口定义。
 */
public interface ConversationSkillRelationService extends IService<ConversationSkillRelation> {

    List<String> listSkillNamesByUserIdAndConversationId(Long userId, String conversationId);

    /**
     * 清空会话现有 skill 绑定，替换为新列表。
     */
    void replaceSkills(Long userId, String conversationId, List<String> skillNames, LocalDateTime now);

    boolean deleteByConversationId(String conversationId);
}
