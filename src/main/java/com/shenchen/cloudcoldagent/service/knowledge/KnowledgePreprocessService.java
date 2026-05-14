package com.shenchen.cloudcoldagent.service.knowledge;

import com.shenchen.cloudcoldagent.model.entity.agent.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;

/**
 * `KnowledgePreprocessService` 接口定义。
 */
public interface KnowledgePreprocessService {

    KnowledgePreprocessResult preprocess(Long userId, ChatConversation conversation, String question);
}
