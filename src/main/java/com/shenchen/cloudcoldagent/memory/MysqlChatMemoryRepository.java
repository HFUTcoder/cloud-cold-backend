package com.shenchen.cloudcoldagent.memory;

import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryImageRelationMapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistoryImageRelation;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.ChatMemoryPendingImageBindingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 MySQL 的 ChatMemoryRepository 实现。
 *
 */
@Slf4j
@Component
@Primary
public class MysqlChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final ChatMemoryHistoryImageRelationMapper chatMemoryHistoryImageRelationMapper;
    private final ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService;

    public MysqlChatMemoryRepository(ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                     ChatMemoryHistoryImageRelationMapper chatMemoryHistoryImageRelationMapper,
                                     ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService) {
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.chatMemoryHistoryImageRelationMapper = chatMemoryHistoryImageRelationMapper;
        this.chatMemoryPendingImageBindingService = chatMemoryPendingImageBindingService;
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

        if (CollectionUtils.isEmpty(messages)) {
            return;
        }

        List<Message> existingMessages = findByConversationId(conversationId);
        int commonPrefixLength = commonPrefixLength(existingMessages, messages);

        if (commonPrefixLength < existingMessages.size()) {
            log.warn("Conversation {} memory snapshot diverged. existingSize={}, incomingSize={}, commonPrefix={}. " +
                            "Falling back to append-only for the unmatched suffix.",
                    conversationId, existingMessages.size(), messages.size(), commonPrefixLength);
        }

        if (commonPrefixLength >= messages.size()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (int i = commonPrefixLength; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message == null || message.getMessageType() == null) {
                continue;
            }
            String content = Objects.toString(message.getText(), "");
            ChatMemoryHistory row = ChatMemoryHistory.builder()
                    .conversationId(conversationId)
                    .content(content)
                    .messageType(message.getMessageType().name())
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            chatMemoryHistoryMapper.insert(row);
            bindPendingImagesIfNeeded(conversationId, message, row, now);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        ChatMemoryHistory updating = ChatMemoryHistory.builder()
                .isDelete(1)
                .build();
        chatMemoryHistoryMapper.updateByQuery(
                updating,
                QueryWrapper.create()
                        .eq("conversationId", conversationId)
                        .eq("isDelete", 0)
        );
        chatMemoryHistoryImageRelationMapper.updateByQuery(
                ChatMemoryHistoryImageRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("conversationId", conversationId)
                        .eq("isDelete", 0)
        );
        chatMemoryPendingImageBindingService.clearPendingImages(conversationId);
    }

    private void bindPendingImagesIfNeeded(String conversationId,
                                           Message message,
                                           ChatMemoryHistory row,
                                           LocalDateTime now) {
        if (message == null || row == null || row.getId() == null || message.getMessageType() != MessageType.ASSISTANT) {
            return;
        }
        List<RetrievedKnowledgeImage> pendingImages = chatMemoryPendingImageBindingService.consumePendingImages(conversationId);
        if (pendingImages == null || pendingImages.isEmpty()) {
            return;
        }
        int sortOrder = 0;
        for (RetrievedKnowledgeImage pendingImage : pendingImages) {
            Long imageId = parseImageId(pendingImage == null ? null : pendingImage.getImageId());
            if (imageId == null || imageId <= 0) {
                continue;
            }
            chatMemoryHistoryImageRelationMapper.insert(ChatMemoryHistoryImageRelation.builder()
                    .historyId(row.getId())
                    .conversationId(conversationId)
                    .imageId(imageId)
                    .sortOrder(sortOrder++)
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build());
        }
    }

    private Long parseImageId(Object imageIdValue) {
        if (imageIdValue == null) {
            return null;
        }
        if (imageIdValue instanceof Number number) {
            return number.longValue();
        }
        String imageIdText = String.valueOf(imageIdValue).trim();
        if (imageIdText.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(imageIdText);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private int commonPrefixLength(List<Message> existingMessages, List<Message> incomingMessages) {
        int prefixLength = 0;
        int limit = Math.min(existingMessages.size(), incomingMessages.size());
        while (prefixLength < limit && sameMessage(existingMessages.get(prefixLength), incomingMessages.get(prefixLength))) {
            prefixLength++;
        }
        return prefixLength;
    }

    private boolean sameMessage(Message left, Message right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.getMessageType() != right.getMessageType()) {
            return false;
        }
        return Objects.equals(Objects.toString(left.getText(), ""), Objects.toString(right.getText(), ""));
    }

}
