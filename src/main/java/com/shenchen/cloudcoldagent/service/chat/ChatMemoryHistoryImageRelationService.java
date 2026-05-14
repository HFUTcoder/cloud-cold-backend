package com.shenchen.cloudcoldagent.service.chat;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatMemoryHistoryImageRelation;

import java.util.List;
import java.util.Map;

/**
 * `ChatMemoryHistoryImageRelationService` 接口定义。
 */
public interface ChatMemoryHistoryImageRelationService extends IService<ChatMemoryHistoryImageRelation> {

    void bindImagesToHistory(Long historyId, String conversationId, List<Long> imageIds);

    List<ChatMemoryHistoryImageRelation> listByHistoryId(Long historyId);

    Map<Long, List<ChatMemoryHistoryImageRelation>> mapByHistoryIds(List<Long> historyIds);

    boolean deleteByHistoryId(Long historyId);

    boolean deleteByConversationId(String conversationId);
}
