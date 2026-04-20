package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;

import java.util.List;

/**
 * 聊天记忆服务层
 */
public interface ChatMemoryHistoryService extends IService<ChatMemoryHistory> {

    /**
     * 根据会话 id 查询历史记录
     */
    List<ChatMemoryHistory> listByConversationId(Long userId, String conversationId);

    /**
     * 根据用户 id 查询该用户所有会话 id
     */
    List<String> listConversationIdsByUserId(Long userId);

    /**
     * 根据历史记录 id 删除记录
     */
    boolean deleteByHistoryId(Long userId, Long id);
}
