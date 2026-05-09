package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistoryImageRelation;

import java.util.List;
import java.util.Map;

/**
 * `ChatMemoryHistoryImageRelationService` 接口定义。
 */
public interface ChatMemoryHistoryImageRelationService extends IService<ChatMemoryHistoryImageRelation> {

    /**
     * 处理 `bind Images To History` 对应逻辑。
     *
     * @param historyId historyId 参数。
     * @param conversationId conversationId 参数。
     * @param imageIds imageIds 参数。
     */
    void bindImagesToHistory(Long historyId, String conversationId, List<Long> imageIds);

    /**
     * 查询 `list By History Id` 对应集合。
     *
     * @param historyId historyId 参数。
     * @return 返回处理结果。
     */
    List<ChatMemoryHistoryImageRelation> listByHistoryId(Long historyId);

    /**
     * 处理 `map By History Ids` 对应逻辑。
     *
     * @param historyIds historyIds 参数。
     * @return 返回处理结果。
     */
    Map<Long, List<ChatMemoryHistoryImageRelation>> mapByHistoryIds(List<Long> historyIds);

    /**
     * 删除 `delete By History Id` 对应内容。
     *
     * @param historyId historyId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByHistoryId(Long historyId);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByConversationId(String conversationId);
}
