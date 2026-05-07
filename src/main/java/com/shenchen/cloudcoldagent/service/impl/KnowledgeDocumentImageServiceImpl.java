package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.KnowledgeDocumentImageMapper;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentImageService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库文档图片服务实现，负责文档图片记录的替换、查询和逻辑删除。
 */
@Service
public class KnowledgeDocumentImageServiceImpl
        extends ServiceImpl<KnowledgeDocumentImageMapper, KnowledgeDocumentImage>
        implements KnowledgeDocumentImageService {

    /**
     * 用新的图片记录列表替换某个文档当前保存的全部图片。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @param documentId 文档 id。
     * @param images 新的图片记录列表。
     * @return 持久化后的图片记录列表。
     */
    @Override
    public List<KnowledgeDocumentImage> replaceDocumentImages(Long userId, Long knowledgeId, Long documentId, List<KnowledgeDocumentImage> images) {
        validateUserId(userId);
        validateKnowledgeId(knowledgeId);
        validateDocumentId(documentId);
        deleteByDocumentId(documentId);
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<KnowledgeDocumentImage> safeImages = new ArrayList<>(images.size());
        for (KnowledgeDocumentImage image : images) {
            if (image == null) {
                continue;
            }
            image.setUserId(userId);
            image.setKnowledgeId(knowledgeId);
            image.setDocumentId(documentId);
            image.setIsDelete(0);
            safeImages.add(image);
        }
        if (!safeImages.isEmpty()) {
            this.saveBatch(safeImages);
        }
        return safeImages;
    }

    /**
     * 查询单张图片记录。
     *
     * @param imageId 图片 id。
     * @return 图片实体。
     */
    @Override
    public KnowledgeDocumentImage getByImageId(Long imageId) {
        validateImageId(imageId);
        KnowledgeDocumentImage image = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("id", imageId)
                .eq("isDelete", 0));
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        return image;
    }

    /**
     * 查询 `list By Image Ids` 对应集合。
     *
     * @param imageIds imageIds 参数。
     * @return 返回处理结果。
     */
    @Override
    public List<KnowledgeDocumentImage> listByImageIds(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return List.of();
        }
        Set<Long> normalizedIds = imageIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeDocumentImage> images = this.mapper.selectListByQuery(QueryWrapper.create()
                .in("id", normalizedIds)
                .eq("isDelete", 0)
                .orderBy("id", true));
        Map<Long, KnowledgeDocumentImage> imageMap = images.stream()
                .filter(image -> image.getId() != null)
                .collect(Collectors.toMap(KnowledgeDocumentImage::getId, image -> image, (left, _right) -> left, LinkedHashMap::new));
        List<KnowledgeDocumentImage> ordered = new ArrayList<>();
        for (Long imageId : imageIds) {
            if (imageId == null || imageId <= 0) {
                continue;
            }
            KnowledgeDocumentImage image = imageMap.get(imageId);
            if (image != null) {
                ordered.add(image);
            }
        }
        return ordered;
    }

    /**
     * 根据图片 id 查询原始图片 URL。
     *
     * @param imageId 图片 id。
     * @return 图片 URL。
     */
    @Override
    public String getImageUrlByImageId(Long imageId) {
        return getByImageId(imageId).getImageUrl();
    }

    /**
     * 按图片 id 列表批量查询图片 URL 映射。
     *
     * @param imageIds 图片 id 列表。
     * @return imageId 到 URL 的映射。
     */
    @Override
    public Map<Long, String> getImageUrlMapByImageIds(List<Long> imageIds) {
        List<KnowledgeDocumentImage> images = listByImageIds(imageIds);
        Map<Long, String> result = new LinkedHashMap<>();
        for (KnowledgeDocumentImage image : images) {
            if (image != null && image.getId() != null) {
                result.put(image.getId(), image.getImageUrl());
            }
        }
        return result;
    }

    /**
     * 查询 `list By Document Id` 对应集合。
     *
     * @param documentId documentId 参数。
     * @return 返回处理结果。
     */
    @Override
    public List<KnowledgeDocumentImage> listByDocumentId(Long documentId) {
        validateDocumentId(documentId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                .eq("documentId", documentId)
                .eq("isDelete", 0)
                .orderBy("imageIndex", true)
                .orderBy("id", true));
    }

    /**
     * 查询 `list By Knowledge Id` 对应集合。
     *
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    @Override
    public List<KnowledgeDocumentImage> listByKnowledgeId(Long knowledgeId) {
        validateKnowledgeId(knowledgeId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                .eq("knowledgeId", knowledgeId)
                .eq("isDelete", 0)
                .orderBy("documentId", true)
                .orderBy("imageIndex", true)
                .orderBy("id", true));
    }

    /**
     * 删除 `delete By Document Id` 对应内容。
     *
     * @param documentId documentId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean deleteByDocumentId(Long documentId) {
        validateDocumentId(documentId);
        return this.mapper.updateByQuery(
                KnowledgeDocumentImage.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("documentId", documentId)
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 删除 `delete By Knowledge Id` 对应内容。
     *
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean deleteByKnowledgeId(Long knowledgeId) {
        validateKnowledgeId(knowledgeId);
        return this.mapper.updateByQuery(
                KnowledgeDocumentImage.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("knowledgeId", knowledgeId)
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 校验 `validate User Id` 对应内容。
     *
     * @param userId userId 参数。
     */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    /**
     * 校验 `validate Knowledge Id` 对应内容。
     *
     * @param knowledgeId knowledgeId 参数。
     */
    private void validateKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null || knowledgeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "knowledgeId 不合法");
        }
    }

    /**
     * 校验 `validate Document Id` 对应内容。
     *
     * @param documentId documentId 参数。
     */
    private void validateDocumentId(Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "documentId 不合法");
        }
    }

    /**
     * 校验 `validate Image Id` 对应内容。
     *
     * @param imageId imageId 参数。
     */
    private void validateImageId(Long imageId) {
        if (imageId == null || imageId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "imageId 不合法");
        }
    }
}
