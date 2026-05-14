package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;

import java.util.List;

/**
 * `UserLongTermMemoryStore` 接口定义。
 */
public interface UserLongTermMemoryStore {

    void addMemories(Long userId, List<UserLongTermMemoryDoc> memories) throws Exception;

    List<UserLongTermMemoryDoc> listByUserId(Long userId, int size) throws Exception;

    List<UserLongTermMemoryDoc> similaritySearch(Long userId, String query, int topK) throws Exception;

    void deleteById(Long userId, String memoryId) throws Exception;

    void deleteByUserId(Long userId) throws Exception;

    /**
     * 批量删除指定 memoryId 的关键词索引和向量索引数据。
     */
    void deleteByIds(Long userId, List<String> memoryIds) throws Exception;

    void deleteByConversationId(Long userId, String conversationId) throws Exception;
}
