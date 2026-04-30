package com.shenchen.cloudcoldagent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.mapper.DocumentMapper;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.enums.DocumentIndexStatusEnum;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import com.shenchen.cloudcoldagent.service.DocumentService;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.MinioService;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, com.shenchen.cloudcoldagent.model.entity.Document>
        implements DocumentService {

    private final KnowledgeService knowledgeService;
    private final MinioService minioService;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;

    public DocumentServiceImpl(KnowledgeService knowledgeService,
                               MinioService minioService,
                               KnowledgeDocumentImageService knowledgeDocumentImageService) {
        this.knowledgeService = knowledgeService;
        this.minioService = minioService;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
    }

    @Override
    public long addDocument(Long userId, DocumentAddRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getDocumentName() == null || request.getDocumentName().isBlank(),
                ErrorCode.PARAMS_ERROR, "文档名称不能为空");

        knowledgeService.getKnowledgeById(userId, request.getKnowledgeId());

        com.shenchen.cloudcoldagent.model.entity.Document document = new com.shenchen.cloudcoldagent.model.entity.Document();
        BeanUtil.copyProperties(request, document);
        document.setUserId(userId);
        if (document.getIndexStatus() == null || document.getIndexStatus().isBlank()) {
            document.setIndexStatus(DocumentIndexStatusEnum.PENDING.getValue());
        }
        if (document.getChunkCount() == null) {
            document.setChunkCount(0);
        }
        boolean result = this.save(document);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建文档失败");
        knowledgeService.refreshKnowledgeStats(userId, request.getKnowledgeId());
        return document.getId();
    }

    @Override
    public boolean updateDocument(Long userId, DocumentUpdateRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);

        com.shenchen.cloudcoldagent.model.entity.Document existing = getDocumentById(userId, request.getId());
        Long oldKnowledgeId = existing.getKnowledgeId();

        if (request.getKnowledgeId() != null) {
            knowledgeService.getKnowledgeById(userId, request.getKnowledgeId());
            existing.setKnowledgeId(request.getKnowledgeId());
        }
        existing.setDocumentName(request.getDocumentName());
        existing.setDocumentUrl(request.getDocumentUrl());
        existing.setObjectName(request.getObjectName());
        existing.setDocumentSource(request.getDocumentSource());
        existing.setFileType(request.getFileType());
        existing.setContentType(request.getContentType());
        existing.setFileSize(request.getFileSize());
        existing.setIndexStatus(request.getIndexStatus());
        existing.setChunkCount(request.getChunkCount());
        existing.setIndexErrorMessage(request.getIndexErrorMessage());
        existing.setIndexStartTime(request.getIndexStartTime());
        existing.setIndexEndTime(request.getIndexEndTime());
        boolean result = this.updateById(existing);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新文档失败");
        knowledgeService.refreshKnowledgeStats(userId, oldKnowledgeId);
        knowledgeService.refreshKnowledgeStats(userId, existing.getKnowledgeId());
        return true;
    }

    @Override
    public boolean deleteDocument(Long userId, Long id) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);

        com.shenchen.cloudcoldagent.model.entity.Document document = getDocumentById(userId, id);
        try {
            try {
                knowledgeService.deleteByDocumentId(document.getId());
            } catch (Exception primaryDeleteException) {
                if (document.getDocumentSource() == null || document.getDocumentSource().isBlank()) {
                    throw primaryDeleteException;
                }
                log.warn("按 documentId 删除文档索引失败，回退按 source 删除。documentId={}, source={}",
                        document.getId(), document.getDocumentSource(), primaryDeleteException);
                knowledgeService.deleteBySource(document.getDocumentSource());
            }
            deleteDocumentImages(document.getId());
            deleteDocumentObject(document);
        } catch (Exception e) {
            log.error("删除文档关联资源失败。documentId={}, documentName={}, source={}, objectName={}",
                    document.getId(),
                    document.getDocumentName(),
                    document.getDocumentSource(),
                    document.getObjectName(),
                    e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除文档关联资源失败");
        }
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除文档失败");
        knowledgeService.refreshKnowledgeStats(userId, document.getKnowledgeId());
        return true;
    }

    @Override
    public com.shenchen.cloudcoldagent.model.entity.Document getDocumentById(Long userId, Long id) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        com.shenchen.cloudcoldagent.model.entity.Document document = this.mapper.selectOneByQuery(
                QueryWrapper.create()
                        .eq("id", id)
                        .eq("userId", userId)
        );
        ThrowUtils.throwIf(document == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        return document;
    }

    @Override
    public List<com.shenchen.cloudcoldagent.model.entity.Document> listByKnowledgeId(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);
        knowledgeService.getKnowledgeById(userId, knowledgeId);
        return this.mapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("knowledgeId", knowledgeId)
                        .orderBy("createTime", false)
        );
    }

    @Override
    public Page<com.shenchen.cloudcoldagent.model.entity.Document> pageByUserId(Long userId, DocumentQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return this.page(Page.of(request.getPageNum(), request.getPageSize()), getQueryWrapper(userId, request));
    }

    @Override
    public QueryWrapper getQueryWrapper(Long userId, DocumentQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return QueryWrapper.create()
                .eq("userId", userId)
                .eq("id", request.getId())
                .eq("knowledgeId", request.getKnowledgeId())
                .like("documentName", request.getDocumentName())
                .eq("fileType", request.getFileType())
                .eq("indexStatus", request.getIndexStatus())
                .orderBy(request.getSortField(), "ascend".equals(request.getSortOrder()));
    }

    @Override
    public DocumentVO getDocumentVO(com.shenchen.cloudcoldagent.model.entity.Document document) {
        if (document == null) {
            return null;
        }
        DocumentVO documentVO = new DocumentVO();
        BeanUtil.copyProperties(document, documentVO);
        return documentVO;
    }

    @Override
    public List<DocumentVO> getDocumentVOList(List<com.shenchen.cloudcoldagent.model.entity.Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        return documents.stream().map(this::getDocumentVO).toList();
    }

    private void deleteDocumentObject(com.shenchen.cloudcoldagent.model.entity.Document document) throws Exception {
        if (document == null || document.getObjectName() == null || document.getObjectName().isBlank()) {
            return;
        }
        try {
            minioService.deleteFile(document.getObjectName());
        } catch (Exception e) {
            if (!isIgnorableDeleteException(e)) {
                throw e;
            }
            log.info("MinIO 原文件已不存在，跳过删除。documentId={}, objectName={}", document.getId(),
                    document.getObjectName());
        }
    }

    private void deleteDocumentImages(Long documentId) throws Exception {
        if (documentId == null || documentId <= 0) {
            return;
        }
        List<KnowledgeDocumentImage> images = knowledgeDocumentImageService.listByDocumentId(documentId);
        for (KnowledgeDocumentImage image : images) {
            if (image == null || image.getObjectName() == null || image.getObjectName().isBlank()) {
                continue;
            }
            try {
                minioService.deleteFile(image.getObjectName());
            } catch (Exception e) {
                if (!isIgnorableDeleteException(e)) {
                    throw e;
                }
                log.info("MinIO 图片对象已不存在，跳过删除。documentId={}, objectName={}",
                        documentId,
                        image.getObjectName());
            }
        }
        knowledgeDocumentImageService.deleteByDocumentId(documentId);
    }

    private boolean isIgnorableDeleteException(Exception exception) {
        if (exception instanceof ErrorResponseException errorResponseException) {
            String code = errorResponseException.errorResponse() == null
                    ? null
                    : errorResponseException.errorResponse().code();
            return "NoSuchKey".equals(code)
                    || "NoSuchObject".equals(code)
                    || "NoSuchBucket".equals(code);
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no such key")
                || normalized.contains("no such object")
                || normalized.contains("no such bucket")
                || normalized.contains("object does not exist")
                || normalized.contains("index_not_found_exception");
    }
}
