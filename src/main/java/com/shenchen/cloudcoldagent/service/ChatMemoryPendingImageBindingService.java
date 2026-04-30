package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.List;

public interface ChatMemoryPendingImageBindingService {

    void registerPendingImages(String conversationId, List<RetrievedKnowledgeImage> images);

    List<RetrievedKnowledgeImage> consumePendingImages(String conversationId);

    void clearPendingImages(String conversationId);
}
