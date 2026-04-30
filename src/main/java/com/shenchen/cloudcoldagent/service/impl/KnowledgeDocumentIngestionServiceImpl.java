package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.config.properties.MinioProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentIndexContext;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.PreparedDocumentIndexResult;
import com.shenchen.cloudcoldagent.enums.DocumentIndexStatusEnum;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import com.shenchen.cloudcoldagent.service.DocumentService;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentIngestionService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeDocumentIngestionServiceImpl implements KnowledgeDocumentIngestionService {

    private final KnowledgeService knowledgeService;
    private final DocumentService documentService;
    private final MinioService minioService;
    private final MinioProperties minioProperties;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeDocumentIngestionServiceImpl(KnowledgeService knowledgeService,
                                                 DocumentService documentService,
                                                 MinioService minioService,
                                                 MinioProperties minioProperties,
                                                 KnowledgeDocumentImageService knowledgeDocumentImageService,
                                                 TransactionTemplate transactionTemplate) {
        this.knowledgeService = knowledgeService;
        this.documentService = documentService;
        this.minioService = minioService;
        this.minioProperties = minioProperties;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public DocumentVO uploadDocument(Long userId, Long knowledgeId, MultipartFile file) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        knowledgeService.getKnowledgeById(userId, knowledgeId);

        String documentName = resolveDocumentName(file);
        String objectName = buildObjectName(knowledgeId, documentName);
        String documentSource = buildDocumentSource(objectName);
        String fileType = extractFileType(documentName);
        String contentType = file.getContentType();
        long fileSize = file.getSize();

        String documentUrl;
        try {
            documentUrl = minioService.uploadFile(file, objectName);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传文件到对象存储失败");
        }

        com.shenchen.cloudcoldagent.model.entity.Document document;
        try {
            DocumentAddRequest addRequest = new DocumentAddRequest();
            addRequest.setKnowledgeId(knowledgeId);
            addRequest.setDocumentName(documentName);
            addRequest.setDocumentUrl(documentUrl);
            addRequest.setObjectName(objectName);
            addRequest.setDocumentSource(documentSource);
            addRequest.setFileType(fileType);
            addRequest.setContentType(contentType);
            addRequest.setFileSize(fileSize);
            addRequest.setIndexStatus(DocumentIndexStatusEnum.PENDING.getValue());
            addRequest.setChunkCount(0);
            long documentId = documentService.addDocument(userId, addRequest);
            document = documentService.getDocumentById(userId, documentId);
        } catch (RuntimeException e) {
            safeDeleteObject(objectName);
            throw e;
        }

        File tempFile = null;
        PreparedDocumentIndexResult preparedResult = null;
        List<KnowledgeDocumentImage> persistedImages = List.of();
        List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> allChunks = List.of();
        try {
            document.setIndexStatus(DocumentIndexStatusEnum.INDEXING.getValue());
            document.setIndexErrorMessage(null);
            document.setIndexStartTime(LocalDateTime.now());
            document.setIndexEndTime(null);
            documentService.updateById(document);

            tempFile = createTempFile(file, documentName);
            preparedResult = knowledgeService.prepareDocumentIndex(
                    tempFile,
                    new DocumentIndexContext(
                            userId,
                            knowledgeId,
                            document.getId(),
                            documentName,
                            objectName,
                            documentSource,
                            fileType,
                            contentType,
                            fileSize
                    )
            );
            persistedImages = persistPreparedImages(userId, knowledgeId, document.getId(), preparedResult.uploadedImages());
            List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> imageChunks =
                    knowledgeService.buildImageDescriptionChunks(persistedImages, documentSource);
            allChunks = mergeChunks(preparedResult.textChunks(), imageChunks);
            knowledgeService.storePreparedChunks(allChunks);

            document.setIndexStatus(DocumentIndexStatusEnum.INDEXED.getValue());
            document.setChunkCount(allChunks.size());
            document.setIndexErrorMessage(null);
            document.setIndexEndTime(LocalDateTime.now());
            documentService.updateById(document);
            return documentService.getDocumentVO(document);
        } catch (Exception e) {
            try {
                knowledgeService.deleteByDocumentId(document.getId());
                knowledgeService.deleteBySource(documentSource);
            } catch (Exception ignored) {
            }
            cleanupPreparedImages(document.getId(), persistedImages.isEmpty()
                    ? (preparedResult == null ? List.of() : preparedResult.uploadedImages())
                    : persistedImages);
            document.setIndexStatus(DocumentIndexStatusEnum.FAILED.getValue());
            document.setIndexErrorMessage(resolveErrorMessage(e));
            document.setIndexEndTime(LocalDateTime.now());
            documentService.updateById(document);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档入库失败: " + resolveErrorMessage(e));
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private String resolveDocumentName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return "document-" + UUID.randomUUID() + ".bin";
        }
        return originalFilename.trim();
    }

    private String buildObjectName(Long knowledgeId, String documentName) {
        String safeFileName = documentName
                .replace("\\", "_")
                .replace("/", "_")
                .replaceAll("\\s+", "_");
        return "knowledge/" + knowledgeId + "/" + System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeFileName;
    }

    private String buildDocumentSource(String objectName) {
        return "minio://" + minioProperties.getBucketName() + "/" + objectName;
    }

    private String extractFileType(String documentName) {
        int dotIndex = documentName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == documentName.length() - 1) {
            return "unknown";
        }
        return documentName.substring(dotIndex + 1).toLowerCase();
    }

    private File createTempFile(MultipartFile multipartFile, String documentName) throws IOException {
        String suffix = ".tmp";
        int dotIndex = documentName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < documentName.length() - 1) {
            suffix = documentName.substring(dotIndex);
        }
        File tempFile = Files.createTempFile("knowledge-document-", suffix).toFile();
        multipartFile.transferTo(tempFile);
        return tempFile;
    }

    private void deleteTempFile(File tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException ignored) {
        }
    }

    private void safeDeleteObject(String objectName) {
        try {
            minioService.deleteFile(objectName);
        } catch (Exception ignored) {
        }
    }

    private List<KnowledgeDocumentImage> persistPreparedImages(Long userId,
                                                               Long knowledgeId,
                                                               Long documentId,
                                                               List<KnowledgeDocumentImage> images) {
        return transactionTemplate.execute(status ->
                knowledgeDocumentImageService.replaceDocumentImages(userId, knowledgeId, documentId, images)
        );
    }

    private void cleanupPreparedImages(Long documentId, List<KnowledgeDocumentImage> uploadedImages) {
        if (uploadedImages != null) {
            for (KnowledgeDocumentImage uploadedImage : uploadedImages) {
                if (uploadedImage == null || uploadedImage.getObjectName() == null || uploadedImage.getObjectName().isBlank()) {
                    continue;
                }
                try {
                    minioService.deleteFile(uploadedImage.getObjectName());
                } catch (Exception ignored) {
                }
            }
        }
        if (documentId != null && documentId > 0) {
            try {
                knowledgeDocumentImageService.deleteByDocumentId(documentId);
            } catch (Exception ignored) {
            }
        }
    }

    private String resolveErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "未知错误";
        }
        return exception.getMessage();
    }

    private List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> mergeChunks(
            List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> textChunks,
            List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> imageChunks) {
        List<com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk> merged = new java.util.ArrayList<>();
        if (textChunks != null && !textChunks.isEmpty()) {
            merged.addAll(textChunks);
        }
        if (imageChunks != null && !imageChunks.isEmpty()) {
            merged.addAll(imageChunks);
        }
        return merged;
    }
}
