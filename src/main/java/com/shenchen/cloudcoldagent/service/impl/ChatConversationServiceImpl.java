package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ChatConversationMapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话服务实现
 */
@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ChatConversationService {

    private static final String DEFAULT_CONVERSATION_TITLE = "新会话";

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final JdbcTemplate jdbcTemplate;

    public ChatConversationServiceImpl(ChatMemoryRepository chatMemoryRepository,
                                       ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                       JdbcTemplate jdbcTemplate) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        return listByUserId(userId).stream()
                .map(ChatConversation::getConversationId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ChatConversation> listByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .orderBy("lastActiveTime", false)
                .orderBy("id", false);
        return this.mapper.selectListByQuery(queryWrapper);
    }

    @Override
    public void touchConversation(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ChatConversation conversation = ChatConversation.builder()
                    .userId(userId)
                    .conversationId(normalizedConversationId)
                    .title(DEFAULT_CONVERSATION_TITLE)
                    .lastActiveTime(now)
                    .isDelete(0)
                    .build();
            this.save(conversation);
            return;
        }

        if (!userId.equals(existing.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
        }
        existing.setLastActiveTime(now);
        this.updateById(existing);
    }

    @Override
    public String createConversation(Long userId) {
        String conversationId = normalizeConversationId(userId, null);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", conversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = ChatConversation.builder()
                .userId(userId)
                .conversationId(conversationId)
                .title(DEFAULT_CONVERSATION_TITLE)
                .lastActiveTime(now)
                .isDelete(0)
                .build();
        this.save(conversation);
        return conversationId;
    }

    @Override
    public boolean deleteConversation(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!userId.equals(existing.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除该会话");
        }

        // 级联删除会话下所有记忆消息
        chatMemoryRepository.deleteByConversationId(normalizedConversationId);

        return jdbcTemplate.update("DELETE FROM chat_conversation WHERE id = ?", existing.getId()) > 0;
    }

    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("userId", userId)
                .eq("isDelete", 0);
        return this.mapper.selectCountByQuery(queryWrapper) > 0;
    }

    @Override
    public String normalizeConversationId(Long userId, String rawConversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (rawConversationId == null || rawConversationId.isBlank()) {
            return "conv_" + UUID.randomUUID().toString().replace("-", "");
        }
        return rawConversationId.trim();
    }

    @Override
    public void generateTitleOnFirstMessage(Long userId, String conversationId, String firstMessage) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper conversationQuery = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation conversation = this.mapper.selectOneByQuery(conversationQuery);
        if (conversation == null) {
            return;
        }
        if (!userId.equals(conversation.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
        }
        if (conversation.getTitle() != null && !DEFAULT_CONVERSATION_TITLE.equals(conversation.getTitle())) {
            return;
        }

        long messageCount = chatMemoryHistoryMapper.selectCountByQuery(QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0));
        if (messageCount > 0) {
            return;
        }

        String safeMessage = firstMessage == null ? "" : firstMessage.trim();
        if (safeMessage.isEmpty()) {
            return;
        }
        String generatedTitle = safeMessage.length() <= 5 ? safeMessage : safeMessage.substring(0, 5);
        conversation.setTitle(generatedTitle);
        this.updateById(conversation);
    }
}
