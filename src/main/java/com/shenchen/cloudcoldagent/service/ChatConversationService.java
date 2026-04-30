package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;

import java.util.List;

/**
 * 会话服务层
 */
public interface ChatConversationService extends IService<ChatConversation> {

    /**
     * 根据 userId 查询该用户的所有 conversationId（按活跃时间倒序）
     */
    List<String> listConversationIdsByUserId(Long userId);

    /**
     * 根据 userId 查询该用户的会话列表（按活跃时间倒序）
     */
    List<ChatConversation> listByUserId(Long userId);

    /**
     * 记录会话活跃（不存在则创建，存在则更新时间）
     */
    void touchConversation(Long userId, String conversationId);

    /**
     * 新建会话并返回 conversationId（conversationId 后端生成，title 默认为“新会话”）
     */
    String createConversation(Long userId);

    /**
     * 删除会话（并删除该会话下消息）
     */
    boolean deleteConversation(Long userId, String conversationId);

    /**
     * 校验会话是否属于该用户
     */
    boolean isConversationOwnedByUser(Long userId, String conversationId);

    /**
     * 获取会话详情
     */
    ChatConversation getByConversationId(Long userId, String conversationId);

    /**
     * 更新会话绑定的 skills
     */
    void updateConversationSkills(Long userId, String conversationId, List<String> selectedSkills);

    /**
     * 更新会话绑定的知识库
     */
    void updateConversationKnowledge(Long userId, String conversationId, Long knowledgeId);

    /**
     * 构建会话级 skill 强约束系统提示词
     */
    String buildConversationSkillPrompt(Long userId, String conversationId);

    /**
     * 规范化会话 id
     */
    String normalizeConversationId(Long userId, String rawConversationId);

    /**
     * 首次对话时自动生成会话标题（前 5 个字）
     */
    void generateTitleOnFirstMessage(Long userId, String conversationId, String firstMessage);
}
