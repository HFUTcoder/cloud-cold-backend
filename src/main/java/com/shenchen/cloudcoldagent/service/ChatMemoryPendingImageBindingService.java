package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.List;

/**
 * `ChatMemoryPendingImageBindingService` 接口定义。
 */
public interface ChatMemoryPendingImageBindingService {

    /**
     * 注册 `register Pending Images` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @param images images 参数。
     */
    void registerPendingImages(String conversationId, List<RetrievedKnowledgeImage> images);

    /**
     * 处理 `consume Pending Images` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    List<RetrievedKnowledgeImage> consumePendingImages(String conversationId);

    /**
     * 处理 `clear Pending Images` 对应逻辑。
     *
     * @param conversationId conversationId 参数。
     */
    void clearPendingImages(String conversationId);
}
