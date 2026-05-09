package com.shenchen.cloudcoldagent.service.knowledge;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;

import java.util.List;
import java.util.Map;

/**
 * `KnowledgeDocumentImageService` 接口定义。
 */
public interface KnowledgeDocumentImageService extends IService<KnowledgeDocumentImage> {

    /**
     * 处理 `replace Document Images` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param documentId documentId 参数。
     * @param images images 参数。
     * @return 返回处理结果。
     */
    List<KnowledgeDocumentImage> replaceDocumentImages(Long userId, Long knowledgeId, Long documentId, List<KnowledgeDocumentImage> images);

    /**
     * 获取 `get By Image Id` 对应结果。
     *
     * @param imageId imageId 参数。
     * @return 返回处理结果。
     */
    KnowledgeDocumentImage getByImageId(Long imageId);

    /**
     * 查询 `list By Image Ids` 对应集合。
     *
     * @param imageIds imageIds 参数。
     * @return 返回处理结果。
     */
    List<KnowledgeDocumentImage> listByImageIds(List<Long> imageIds);

    /**
     * 获取 `get Image Url By Image Id` 对应结果。
     *
     * @param imageId imageId 参数。
     * @return 返回处理结果。
     */
    String getImageUrlByImageId(Long imageId);

    /**
     * 获取 `get Image Url Map By Image Ids` 对应结果。
     *
     * @param imageIds imageIds 参数。
     * @return 返回处理结果。
     */
    Map<Long, String> getImageUrlMapByImageIds(List<Long> imageIds);

    /**
     * 查询 `list By Document Id` 对应集合。
     *
     * @param documentId documentId 参数。
     * @return 返回处理结果。
     */
    List<KnowledgeDocumentImage> listByDocumentId(Long documentId);

    /**
     * 查询 `list By Knowledge Id` 对应集合。
     *
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    List<KnowledgeDocumentImage> listByKnowledgeId(Long knowledgeId);

    /**
     * 删除 `delete By Document Id` 对应内容。
     *
     * @param documentId documentId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByDocumentId(Long documentId);

    /**
     * 删除 `delete By Knowledge Id` 对应内容。
     *
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByKnowledgeId(Long knowledgeId);
}
