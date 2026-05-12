package com.shenchen.cloudcoldagent.memory.store;

import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryImageRelationMapper;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistoryImageRelation;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.chat.ChatMemoryPendingImageBindingService;
import com.shenchen.cloudcoldagent.service.chat.UserConversationRelationService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final UserConversationRelationService userConversationRelationService;
    private final UserLongTermMemoryService userLongTermMemoryService;

    /**
     * 创建 `MysqlChatMemoryRepository` 实例。
     *
     * @param chatMemoryHistoryMapper chatMemoryHistoryMapper 参数。
     * @param chatMemoryHistoryImageRelationMapper chatMemoryHistoryImageRelationMapper 参数。
     * @param chatMemoryPendingImageBindingService chatMemoryPendingImageBindingService 参数。
     * @param userConversationRelationService userConversationRelationService 参数。
     * @param userLongTermMemoryService userLongTermMemoryService 参数。
     */
    public MysqlChatMemoryRepository(ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                     ChatMemoryHistoryImageRelationMapper chatMemoryHistoryImageRelationMapper,
                                     ChatMemoryPendingImageBindingService chatMemoryPendingImageBindingService,
                                     UserConversationRelationService userConversationRelationService,
                                     UserLongTermMemoryService userLongTermMemoryService) {
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.chatMemoryHistoryImageRelationMapper = chatMemoryHistoryImageRelationMapper;
        this.chatMemoryPendingImageBindingService = chatMemoryPendingImageBindingService;
        this.userConversationRelationService = userConversationRelationService;
        this.userLongTermMemoryService = userLongTermMemoryService;
    }

    /**
     * 查找 `find Conversation Ids` 对应结果。
     *
     * @return 返回处理结果。
     */
    @Override
    public List<String> findConversationIds() {
        return chatMemoryHistoryMapper.selectDistinctConversationIds();
    }

    /**
     * 查找 `find By Conversation Id` 对应结果。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `save All` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param messages messages 参数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            final String cid = conversationId;
            final List<Message> msgs = messages;
            final int prefixLen = commonPrefixLength;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifyLongTermMemory(cid, msgs, prefixLen);
                }
            });
        } else {
            notifyLongTermMemory(conversationId, messages, commonPrefixLength);
        }
    }

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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

    /**
     * 处理 `bind Pending Images If Needed` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param message message 参数。
     * @param row row 参数。
     * @param now now 参数。
     */
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

    /**
     * 解析 `parse Image Id` 对应内容。
     *
     * @param imageIdValue imageIdValue 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `to Message` 对应逻辑。
     *
     * @param row row 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `common Prefix Length` 对应逻辑。
     *
     * @param existingMessages existingMessages 参数。
     * @param incomingMessages incomingMessages 参数。
     * @return 返回处理结果。
     */
    private int commonPrefixLength(List<Message> existingMessages, List<Message> incomingMessages) {
        int prefixLength = 0;
        int limit = Math.min(existingMessages.size(), incomingMessages.size());
        while (prefixLength < limit && sameMessage(existingMessages.get(prefixLength), incomingMessages.get(prefixLength))) {
            prefixLength++;
        }
        return prefixLength;
    }

    /**
     * 处理 `same Message` 对应逻辑。
     *
     * @param left left 参数。
     * @param right right 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `notify Long Term Memory` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @param messages messages 参数。
     * @param commonPrefixLength commonPrefixLength 参数。
     */
    private void notifyLongTermMemory(String conversationId, List<Message> messages, int commonPrefixLength) {
        if (conversationId == null || conversationId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        long assistantMessageCount = messages.stream()
                .skip(commonPrefixLength)
                .filter(message -> message != null && message.getMessageType() == MessageType.ASSISTANT)
                .count();
        if (assistantMessageCount <= 0) {
            return;
        }
        Long userId = userConversationRelationService.getUserIdByConversationId(conversationId);
        if (userId == null || userId <= 0) {
            return;
        }
        userLongTermMemoryService.onAssistantMessagePersisted(userId, conversationId, (int) assistantMessageCount);
    }

}
