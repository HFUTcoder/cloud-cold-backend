package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;

import java.util.List;

/**
 * `UserLongTermMemoryStore` 接口定义。
 */
public interface UserLongTermMemoryStore {

    /**
     * 处理 `ensure Indexes` 对应逻辑。
     */
    void ensureIndexes();

    /**
     * 处理 `add Memories` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param memories memories 参数。
     * @throws Exception 异常信息。
     */
    void addMemories(Long userId, List<UserLongTermMemoryDoc> memories) throws Exception;

    /**
     * 查询 `list By User Id` 对应集合。
     *
     * @param userId userId 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<UserLongTermMemoryDoc> listByUserId(Long userId, int size) throws Exception;

    /**
     * 处理 `similarity Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param query query 参数。
     * @param topK topK 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<UserLongTermMemoryDoc> similaritySearch(Long userId, String query, int topK) throws Exception;

    /**
     * 删除 `delete By Id` 对应内容。
     *
     * @param userId userId 参数。
     * @param memoryId memoryId 参数。
     * @throws Exception 异常信息。
     */
    void deleteById(Long userId, String memoryId) throws Exception;

    /**
     * 删除 `delete By User Id` 对应内容。
     *
     * @param userId userId 参数。
     * @throws Exception 异常信息。
     */
    void deleteByUserId(Long userId) throws Exception;

    /**
     * 批量删除指定 memoryId 的关键词索引和向量索引数据。
     *
     * @param userId userId 参数。
     * @param memoryIds memoryId 列表。
     * @throws Exception 异常信息。
     */
    void deleteByIds(Long userId, List<String> memoryIds) throws Exception;

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @throws Exception 异常信息。
     */
    void deleteByConversationId(Long userId, String conversationId) throws Exception;
}
