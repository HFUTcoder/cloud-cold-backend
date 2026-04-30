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

@Service
public class KnowledgeDocumentImageServiceImpl
        extends ServiceImpl<KnowledgeDocumentImageMapper, KnowledgeDocumentImage>
        implements KnowledgeDocumentImageService {

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

    @Override
    public String getImageUrlByImageId(Long imageId) {
        return getByImageId(imageId).getImageUrl();
    }

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

    @Override
    public List<KnowledgeDocumentImage> listByDocumentId(Long documentId) {
        validateDocumentId(documentId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                .eq("documentId", documentId)
                .eq("isDelete", 0)
                .orderBy("imageIndex", true)
                .orderBy("id", true));
    }

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

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    private void validateKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null || knowledgeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "knowledgeId 不合法");
        }
    }

    private void validateDocumentId(Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "documentId 不合法");
        }
    }

    private void validateImageId(Long imageId) {
        if (imageId == null || imageId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "imageId 不合法");
        }
    }
}
