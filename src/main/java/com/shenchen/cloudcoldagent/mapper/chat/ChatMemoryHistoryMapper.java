package com.shenchen.cloudcoldagent.mapper.chat;

import com.mybatisflex.core.BaseMapper;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatMemoryHistory;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天记忆映射层
 */
public interface ChatMemoryHistoryMapper extends BaseMapper<ChatMemoryHistory> {

    @Select("SELECT conversationId FROM chat_memory_history WHERE isDelete = 0 AND conversationId IS NOT NULL AND conversationId <> '' GROUP BY conversationId ORDER BY MIN(createTime)")
    List<String> selectDistinctConversationIds();
}
