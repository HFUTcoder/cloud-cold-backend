package com.shenchen.cloudcoldagent.memory;

import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.mapper.ChatConversationMapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 MySQL（MyBatis Flex）的 ChatMemoryRepository 实现。
 *
 * 说明：
 * - Spring AI 1.1.x 的 MessageWindowChatMemory 支持通过 builder.chatMemoryRepository(...) 注入持久化仓库。
 * - 该实现将消息写入 chat_memory_history 表，并按 createTime 顺序读取。
 */
@Slf4j
@Component
public class MysqlChatMemoryRepository implements ChatMemoryRepository {

    private final ChatConversationMapper chatConversationMapper;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final JdbcTemplate jdbcTemplate;

    public MysqlChatMemoryRepository(ChatConversationMapper chatConversationMapper,
                                     ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                     JdbcTemplate jdbcTemplate) {
        this.chatConversationMapper = chatConversationMapper;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("isDelete", 0)
                .orderBy("createTime", true);
        List<ChatMemoryHistory> rows = chatMemoryHistoryMapper.selectListByQuery(queryWrapper);
        Set<String> conversationIds = new LinkedHashSet<>();
        for (ChatMemoryHistory row : rows) {
            if (row.getConversationId() != null && !row.getConversationId().isBlank()) {
                conversationIds.add(row.getConversationId());
            }
        }
        return new ArrayList<>(conversationIds);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", conversationId)
                .eq("isDelete", 0)
                .orderBy("createTime", true)
                .orderBy("id", true);
        List<ChatMemoryHistory> rows = chatMemoryHistoryMapper.selectListByQuery(queryWrapper);
        if (CollectionUtils.isEmpty(rows)) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(rows.size());
        for (ChatMemoryHistory row : rows) {
            Message message = toMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        // 与 Spring AI ChatMemoryRepository 语义保持一致：保存完整快照（覆盖旧记录）
        deleteByConversationId(conversationId);

        if (CollectionUtils.isEmpty(messages)) {
            return;
        }

        Long userId = getUserIdByConversationId(conversationId);
        LocalDateTime now = LocalDateTime.now();

        for (Message message : messages) {
            if (message == null || message.getMessageType() == null) {
                continue;
            }
            String content = Objects.toString(message.getText(), "");
            ChatMemoryHistory row = ChatMemoryHistory.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .content(content)
                    .messageType(message.getMessageType().name())
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            chatMemoryHistoryMapper.insert(row);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM chat_memory_history WHERE conversationId = ?", conversationId);
    }

    private Message toMessage(ChatMemoryHistory row) {
        MessageType messageType;
        try {
            messageType = MessageType.valueOf(row.getMessageType());
        } catch (Exception e) {
            log.warn("Unknown message type: {}, fallback to USER", row.getMessageType());
            messageType = MessageType.USER;
        }

        String content = Objects.toString(row.getContent(), "");
        return switch (messageType) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case TOOL -> new AssistantMessage(content);
        };
    }

    private Long getUserIdByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        ChatConversation conversation = chatConversationMapper.selectOneByQuery(QueryWrapper.create()
                .eq("conversationId", conversationId)
                .eq("isDelete", 0));
        if (conversation == null) {
            return null;
        }
        return conversation.getUserId();
    }
}
