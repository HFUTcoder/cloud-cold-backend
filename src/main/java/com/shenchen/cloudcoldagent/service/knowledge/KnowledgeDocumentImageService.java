package com.shenchen.cloudcoldagent.service.knowledge;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.knowledge.KnowledgeDocumentImage;

import java.util.List;
import java.util.Map;

/**
 * `KnowledgeDocumentImageService` 接口定义。
 */
public interface KnowledgeDocumentImageService extends IService<KnowledgeDocumentImage> {

    List<KnowledgeDocumentImage> replaceDocumentImages(Long userId, Long knowledgeId, Long documentId, List<KnowledgeDocumentImage> images);

    KnowledgeDocumentImage getByImageId(Long imageId);

    List<KnowledgeDocumentImage> listByImageIds(List<Long> imageIds);

    String getImageUrlByImageId(Long imageId);

    Map<Long, String> getImageUrlMapByImageIds(List<Long> imageIds);

    List<KnowledgeDocumentImage> listByDocumentId(Long documentId);

    List<KnowledgeDocumentImage> listByKnowledgeId(Long knowledgeId);

    boolean deleteByDocumentId(Long documentId);

    boolean deleteByKnowledgeId(Long knowledgeId);
}
