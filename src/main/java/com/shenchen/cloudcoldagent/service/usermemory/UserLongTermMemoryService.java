package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserLongTermMemoryVO;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserPetStateVO;

import java.util.List;

/**
 * `UserLongTermMemoryService` 接口定义。
 */
public interface UserLongTermMemoryService {

    /**
     * 获取 `get Pet State` 对应结果。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    UserPetStateVO getPetState(Long userId);

    /**
     * 查询 `list Memories` 对应集合。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    List<UserLongTermMemoryVO> listMemories(Long userId);

    /**
     * 处理 `trigger Manual Rebuild` 对应逻辑。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    boolean triggerManualRebuild(Long userId);

    /**
     * 处理 `rename Pet` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param petName petName 参数。
     * @return 返回处理结果。
     */
    boolean renamePet(Long userId, String petName);

    /**
     * 删除 `delete Memory` 对应内容。
     *
     * @param userId userId 参数。
     * @param memoryId memoryId 参数。
     * @return 返回处理结果。
     */
    boolean deleteMemory(Long userId, String memoryId);

    /**
     * 处理 `on Assistant Message Persisted` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param assistantMessageCount assistantMessageCount 参数。
     */
    void onAssistantMessagePersisted(Long userId, String conversationId, int assistantMessageCount);

    /**
     * 处理 `on Conversation Deleted` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void onConversationDeleted(Long userId, String conversationId);

    /**
     * 处理 `on History Deleted` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    void onHistoryDeleted(Long userId, String conversationId);

    /**
     * 处理 `retrieve Relevant Memories` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param question question 参数。
     * @param topK topK 参数。
     * @return 返回处理结果。
     */
    List<UserLongTermMemoryDoc> retrieveRelevantMemories(Long userId, String question, int topK);

    /**
     * 处理 `process Pending Conversations` 对应逻辑。
     *
     * @param userId userId 参数。
     */
    void processPendingConversations(Long userId);
}
