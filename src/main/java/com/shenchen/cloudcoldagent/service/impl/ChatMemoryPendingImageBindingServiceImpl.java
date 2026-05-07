package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.ChatMemoryPendingImageBindingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待绑定知识库图片服务实现，用于在 Agent 回答落库前暂存本轮命中的图片列表。
 */
@Service
public class ChatMemoryPendingImageBindingServiceImpl implements ChatMemoryPendingImageBindingService {

    private final Map<String, List<RetrievedKnowledgeImage>> pendingImagesByConversation = new ConcurrentHashMap<>();

    /**
     * 为当前会话注册一批待绑定到历史消息的知识库图片。
     *
     * @param conversationId 会话 id。
     * @param images 待绑定图片列表。
     */
    @Override
    public void registerPendingImages(String conversationId, List<RetrievedKnowledgeImage> images) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (images == null || images.isEmpty()) {
            pendingImagesByConversation.remove(conversationId);
            return;
        }
        pendingImagesByConversation.put(conversationId, new ArrayList<>(images));
    }

    /**
     * 读取并消费当前会话暂存的待绑定图片列表。
     *
     * @param conversationId 会话 id。
     * @return 当前会话待绑定图片；无数据时返回空列表。
     */
    @Override
    public List<RetrievedKnowledgeImage> consumePendingImages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<RetrievedKnowledgeImage> images = pendingImagesByConversation.remove(conversationId);
        return images == null ? List.of() : images;
    }

    /**
     * 清空当前会话暂存的待绑定图片。
     *
     * @param conversationId 会话 id。
     */
    @Override
    public void clearPendingImages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        pendingImagesByConversation.remove(conversationId);
    }
}
