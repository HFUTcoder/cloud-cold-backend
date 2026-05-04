package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryConversationState;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;

import java.util.List;
import java.util.Map;

public interface UserLongTermMemoryMetadataService {

    void upsertMemories(Long userId, String conversationId, List<UserLongTermMemoryDoc> memories);

    List<UserLongTermMemory> listActiveByUserId(Long userId, int size);

    Map<String, UserLongTermMemory> mapActiveByMemoryIds(Long userId, List<String> memoryIds);

    Map<String, List<UserLongTermMemorySourceRelation>> mapSourcesByMemoryIds(List<String> memoryIds);

    void markRetrieved(Long userId, List<String> memoryIds);

    boolean deleteMemory(Long userId, String memoryId);

    void softDeleteByUserId(Long userId);

    void deleteByConversationId(Long userId, String conversationId);

    void ensureConversationState(Long userId, String conversationId);

    void incrementPendingRounds(Long userId, String conversationId, int roundCount);

    void markConversationUnprocessed(Long userId, String conversationId);

    void deleteConversationState(Long userId, String conversationId);

    List<UserLongTermMemoryConversationState> listPendingConversationStates(Long userId);

    List<Long> listUserIdsWithPendingConversationStates();

    void markConversationProcessed(Long userId, String conversationId);
}
