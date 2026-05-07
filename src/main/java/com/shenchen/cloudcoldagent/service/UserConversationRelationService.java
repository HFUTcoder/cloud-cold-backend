package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.UserConversationRelation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `UserConversationRelationService` 接口定义。
 */
public interface UserConversationRelationService extends IService<UserConversationRelation> {

    /**
     * 查询 `list Conversation Ids By User Id` 对应集合。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    List<String> listConversationIdsByUserId(Long userId);

    /**
     * 获取 `get User Id By Conversation Id` 对应结果。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    Long getUserIdByConversationId(String conversationId);

    /**
     * 判断 `is Conversation Owned By User` 条件是否成立。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean isConversationOwnedByUser(Long userId, String conversationId);

    /**
     * 处理 `bind User Conversation` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param now now 参数。
     */
    void bindUserConversation(Long userId, String conversationId, LocalDateTime now);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByConversationId(String conversationId);
}
