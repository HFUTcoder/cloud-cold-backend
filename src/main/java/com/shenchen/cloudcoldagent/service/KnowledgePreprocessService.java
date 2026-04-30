package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;

public interface KnowledgePreprocessService {

    KnowledgePreprocessResult preprocess(Long userId, ChatConversation conversation, String question);
}
