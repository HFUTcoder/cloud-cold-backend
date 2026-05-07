package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ConversationSkillRelation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `ConversationSkillRelationService` 接口定义。
 */
public interface ConversationSkillRelationService extends IService<ConversationSkillRelation> {

    /**
     * 查询 `list Skill Names By User Id And Conversation Id` 对应集合。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    List<String> listSkillNamesByUserIdAndConversationId(Long userId, String conversationId);

    /**
     * 处理 `replace Skills` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param skillNames skillNames 参数。
     * @param now now 参数。
     */
    void replaceSkills(Long userId, String conversationId, List<String> skillNames, LocalDateTime now);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByConversationId(String conversationId);
}
