package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryConversationState;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;

import java.util.List;
import java.util.Map;

/**
 * `UserLongTermMemoryMetadataService` 接口定义。
 */
public interface UserLongTermMemoryMetadataService {

    /**
     * 处理 `upsert Memories` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param memories memories 参数。
     */
    void upsertMemories(Long userId, String conversationId, List<UserLongTermMemoryDoc> memories);

    /**
     * 查询 `list Active By User Id` 对应集合。
     *
     * @param userId userId 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     */
    List<UserLongTermMemory> listActiveByUserId(Long userId, int size);

    /**
     * 处理 `map Active By Memory Ids` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param memoryIds memoryIds 参数。
     * @return 返回处理结果。
     */
    Map<String, UserLongTermMemory> mapActiveByMemoryIds(Long userId, List<String> memoryIds);

    /**
     * 处理 `map Active Docs By Memory Ids` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param memoryIds memoryIds 参数。
     * @return 返回处理结果。
     */
    Map<String, UserLongTermMemoryDoc> mapActiveDocsByMemoryIds(Long userId, List<String> memoryIds);

    /**
     * 处理 `map Sources By Memory Ids` 对应逻辑。
     *
     * @param memoryIds memoryIds 参数。
     * @return 返回处理结果。
     */
    Map<String, List<UserLongTermMemorySourceRelation>> mapSourcesByMemoryIds(List<String> memoryIds);

    /**
     * 处理 `mark Retrieved` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param memoryIds memoryIds 参数。
     */
    void markRetrieved(Long userId, List<String> memoryIds);

    /**
     * 删除 `delete Memory` 对应内容。
     *
     * @param userId userId 参数。
     * @param memoryId memoryId 参数。
     * @return 返回处理结果。
     */
    boolean deleteMemory(Long userId, String memoryId);

    /**
     * 处理 `soft Delete By User Id` 对应逻辑。
     *
     * @param userId userId 参数。
     */
    void softDeleteByUserId(Long userId);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void deleteByConversationId(Long userId, String conversationId);

    /**
     * 处理 `ensure Conversation State` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void ensureConversationState(Long userId, String conversationId);

    /**
     * 处理 `increment Pending Rounds` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param roundCount roundCount 参数。
     */
    void incrementPendingRounds(Long userId, String conversationId, int roundCount);

    /**
     * 处理 `mark Conversation Unprocessed` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void markConversationUnprocessed(Long userId, String conversationId);

    /**
     * 处理 `mark All User Conversations Unprocessed` 对应逻辑。
     *
     * @param userId userId 参数。
     */
    void markAllUserConversationsUnprocessed(Long userId);

    /**
     * 删除 `delete Conversation State` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void deleteConversationState(Long userId, String conversationId);

    /**
     * 查询 `list Pending Conversation States` 对应集合。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    List<UserLongTermMemoryConversationState> listPendingConversationStates(Long userId);

    /**
     * 查询 `list User Ids With Pending Conversation States` 对应集合。
     *
     * @return 返回处理结果。
     */
    List<Long> listUserIdsWithPendingConversationStates();

    /**
     * 处理 `mark Conversation Processed` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void markConversationProcessed(Long userId, String conversationId);
}
