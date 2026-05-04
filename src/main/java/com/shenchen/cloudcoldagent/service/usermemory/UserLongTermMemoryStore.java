package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;

import java.util.List;

public interface UserLongTermMemoryStore {

    void ensureIndexes();

    void addMemories(Long userId, List<UserLongTermMemoryDoc> memories) throws Exception;

    List<UserLongTermMemoryDoc> listByUserId(Long userId, int size) throws Exception;

    List<UserLongTermMemoryDoc> similaritySearch(Long userId, String query, int topK) throws Exception;

    void deleteById(Long userId, String memoryId) throws Exception;

    void deleteByUserId(Long userId) throws Exception;

    void deleteByConversationId(Long userId, String conversationId) throws Exception;
}
