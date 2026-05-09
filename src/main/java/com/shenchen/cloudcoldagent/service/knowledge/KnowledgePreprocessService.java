package com.shenchen.cloudcoldagent.service.knowledge;

import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;

/**
 * `KnowledgePreprocessService` 接口定义。
 */
public interface KnowledgePreprocessService {

    /**
     * 预处理 `preprocess` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversation conversation 参数。
     * @param question question 参数。
     * @return 返回处理结果。
     */
    KnowledgePreprocessResult preprocess(Long userId, ChatConversation conversation, String question);
}
