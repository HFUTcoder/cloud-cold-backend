package com.shenchen.cloudcoldagent.service.knowledge.impl;

import com.shenchen.cloudcoldagent.config.properties.MinioProperties;
import com.shenchen.cloudcoldagent.constant.KnowledgeChunkConstant;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentIndexContext;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.PreparedDocumentIndexResult;
import com.shenchen.cloudcoldagent.enums.DocumentIndexStatusEnum;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import com.shenchen.cloudcoldagent.service.knowledge.DocumentService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentIngestionService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.storage.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 知识库文档入库服务实现，负责上传原始文件、创建文档记录、解析内容并同步写入索引。
 */
@Service
public class KnowledgeDocumentIngestionServiceImpl implements KnowledgeDocumentIngestionService {

    private final KnowledgeService knowledgeService;
    private final DocumentService documentService;
    private final MinioService minioService;
    private final MinioProperties minioProperties;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 注入文档入库链路所需的依赖服务。
     *
     * @param knowledgeService 知识库业务服务。
     * @param documentService 文档业务服务。
     * @param minioService MinIO 文件服务。
     * @param minioProperties MinIO 配置。
     * @param knowledgeDocumentImageService 文档图片业务服务。
     * @param transactionTemplate 事务模板。
     */
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

    /**
     * 上传文档并同步执行知识库入库主链路。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 目标知识库 id。
     * @param file 上传文件。
     * @return 入库完成后的文档 VO。
     */
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
            allChunks = new java.util.ArrayList<>();
            allChunks.addAll(preparedResult.parentChunks());
            allChunks.addAll(preparedResult.childChunks());
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

    /**
     * 解析上传文件的展示名称。
     *
     * @param file 上传文件。
     * @return 解析后的文档名；无法获取时返回默认名称。
     */
    private String resolveDocumentName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return "document-" + UUID.randomUUID() + ".bin";
        }
        return originalFilename.trim();
    }

    /**
     * 构建上传到对象存储中的对象名称。
     *
     * @param knowledgeId 知识库 id。
     * @param documentName 文档名。
     * @return MinIO 对象名。
     */
    private String buildObjectName(Long knowledgeId, String documentName) {
        String safeFileName = documentName
                .replace("\\", "_")
                .replace("/", "_")
                .replaceAll("\\s+", "_");
        return KnowledgeChunkConstant.OBJECT_PREFIX_KNOWLEDGE + knowledgeId + "/" + System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeFileName;
    }

    /**
     * 构建写入索引元数据中的文档来源标识。
     *
     * @param objectName MinIO 对象名。
     * @return 统一格式的文档来源字符串。
     */
    private String buildDocumentSource(String objectName) {
        return "minio://" + minioProperties.getBucketName() + "/" + objectName;
    }

    /**
     * 从文件名中提取文件扩展名。
     *
     * @param documentName 文档名。
     * @return 文件类型；无法识别时返回 unknown。
     */
    private String extractFileType(String documentName) {
        int dotIndex = documentName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == documentName.length() - 1) {
            return "unknown";
        }
        return documentName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 将上传文件落到本地临时文件，供解析器读取。
     *
     * @param multipartFile 上传文件。
     * @param documentName 文档名。
     * @return 本地临时文件。
     * @throws IOException 创建或写入临时文件失败时抛出。
     */
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

    /**
     * 删除入库过程中创建的本地临时文件。
     *
     * @param tempFile 临时文件。
     */
    private void deleteTempFile(File tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException ignored) {
        }
    }

    /**
     * 尝试删除已经上传到对象存储的原始文件，忽略清理阶段的异常。
     *
     * @param objectName MinIO 对象名。
     */
    private void safeDeleteObject(String objectName) {
        try {
            minioService.deleteFile(objectName);
        } catch (Exception ignored) {
        }
    }

    /**
     * 以事务方式保存解析阶段抽取出的文档图片记录。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @param documentId 文档 id。
     * @param images 解析阶段抽取出的图片记录。
     * @return 持久化后的图片列表。
     */
    private List<KnowledgeDocumentImage> persistPreparedImages(Long userId,
                                                               Long knowledgeId,
                                                               Long documentId,
                                                               List<KnowledgeDocumentImage> images) {
        return transactionTemplate.execute(status ->
                knowledgeDocumentImageService.replaceDocumentImages(userId, knowledgeId, documentId, images)
        );
    }

    /**
     * 清理入库失败时已经上传或保存的图片资源。
     *
     * @param documentId 文档 id。
     * @param uploadedImages 已上传的图片列表。
     */
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

    /**
     * 统一提取异常中可用于返回给前端的错误信息。
     *
     * @param exception 原始异常。
     * @return 可读的错误文本。
     */
    private String resolveErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "未知错误";
        }
        return exception.getMessage();
    }

}
