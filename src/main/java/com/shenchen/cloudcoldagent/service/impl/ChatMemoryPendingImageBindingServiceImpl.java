package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.ChatMemoryPendingImageBindingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatMemoryPendingImageBindingServiceImpl implements ChatMemoryPendingImageBindingService {

    private final Map<String, List<RetrievedKnowledgeImage>> pendingImagesByConversation = new ConcurrentHashMap<>();

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

    @Override
    public List<RetrievedKnowledgeImage> consumePendingImages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<RetrievedKnowledgeImage> images = pendingImagesByConversation.remove(conversationId);
        return images == null ? List.of() : images;
    }

    @Override
    public void clearPendingImages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        pendingImagesByConversation.remove(conversationId);
    }
}
