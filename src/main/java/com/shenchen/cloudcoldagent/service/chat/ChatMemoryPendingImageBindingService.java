package com.shenchen.cloudcoldagent.service.chat;

import com.shenchen.cloudcoldagent.model.vo.agent.RetrievedKnowledgeImage;

import java.util.List;

/**
 * `ChatMemoryPendingImageBindingService` 接口定义。
 */
public interface ChatMemoryPendingImageBindingService {

    void registerPendingImages(String conversationId, List<RetrievedKnowledgeImage> images);

    /**
     * 读取并消费当前会话暂存的待绑定图片列表（读后即清）。
     */
    List<RetrievedKnowledgeImage> consumePendingImages(String conversationId);

    void clearPendingImages(String conversationId);
}
