package com.shenchen.cloudcoldagent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.bean.BeanUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.document.extract.cleaner.DocumentCleaner;
import com.shenchen.cloudcoldagent.document.extract.reader.DocumentReaderFactory;
import com.shenchen.cloudcoldagent.document.load.store.StoreService;
import com.shenchen.cloudcoldagent.document.transform.splitter.OverlapParagraphTextSplitter;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.mapper.DocumentMapper;
import com.shenchen.cloudcoldagent.mapper.KnowledgeMapper;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.Knowledge;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentReadResult;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentIndexContext;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.ExtractedDocumentImage;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.PreparedDocumentIndexResult;
import com.shenchen.cloudcoldagent.model.vo.KnowledgeVO;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.MinioService;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

@Service
@Slf4j
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, Knowledge> implements KnowledgeService {

    private static final int DEFAULT_CHUNK_SIZE = 200;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;
    private static final int DEFAULT_SCALAR_TOP_K = 5;
    private static final int DEFAULT_VECTOR_TOP_K = 5;
    private static final double DEFAULT_ACCEPT_ALL_THRESHOLD = 0.5;
    private static final int HYBRID_RRF_K = 60;

    private final DocumentReaderFactory documentReaderFactory;
    private final ElasticSearchService elasticSearchService;
    private final StoreService storeService;
    private final DocumentMapper documentMapper;
    private final MinioService minioService;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;

    public KnowledgeServiceImpl(DocumentReaderFactory documentReaderFactory,
                                ElasticSearchService elasticSearchService,
                                StoreService storeService,
                                DocumentMapper documentMapper,
                                MinioService minioService,
                                KnowledgeDocumentImageService knowledgeDocumentImageService) {
        this.documentReaderFactory = documentReaderFactory;
        this.elasticSearchService = elasticSearchService;
        this.storeService = storeService;
        this.documentMapper = documentMapper;
        this.minioService = minioService;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
    }

    @Override
    public long createKnowledge(Long userId, KnowledgeAddRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getKnowledgeName() == null || request.getKnowledgeName().isBlank(), ErrorCode.PARAMS_ERROR, "知识库名称不能为空");

        Knowledge knowledge = Knowledge.builder()
                .userId(userId)
                .knowledgeName(request.getKnowledgeName().trim())
                .description(request.getDescription())
                .documentCount(0)
                .build();
        boolean result = this.save(knowledge);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建知识库失败");
        return knowledge.getId();
    }

    @Override
    public boolean updateKnowledge(Long userId, KnowledgeUpdateRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);

        Knowledge existing = getKnowledgeById(userId, request.getId());
        existing.setKnowledgeName(request.getKnowledgeName());
        existing.setDescription(request.getDescription());
        return this.updateById(existing);
    }

    @Override
    public boolean deleteKnowledge(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);

        Knowledge knowledge = getKnowledgeById(userId, knowledgeId);
        List<com.shenchen.cloudcoldagent.model.entity.Document> documents = documentMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("knowledgeId", knowledgeId)
        );
        for (com.shenchen.cloudcoldagent.model.entity.Document document : documents) {
            try {
                deleteDocumentIndex(document);
            } catch (Exception e) {
                log.error("删除知识库关联资源失败。knowledgeId={}, documentId={}, documentName={}, source={}, objectName={}",
                        knowledgeId,
                        document.getId(),
                        document.getDocumentName(),
                        document.getDocumentSource(),
                        document.getObjectName(),
                        e);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除知识库关联的索引内容失败: " + document.getDocumentName());
            }
        }
        documentMapper.deleteByQuery(QueryWrapper.create().eq("knowledgeId", knowledgeId).eq("userId", userId));
        knowledgeDocumentImageService.deleteByKnowledgeId(knowledgeId);
        return this.removeById(knowledge.getId());
    }

    @Override
    public Knowledge getKnowledgeById(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);
        Knowledge knowledge = this.mapper.selectOneByQuery(
                QueryWrapper.create()
                        .eq("id", knowledgeId)
                        .eq("userId", userId)
        );
        ThrowUtils.throwIf(knowledge == null, ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        return knowledge;
    }

    @Override
    public Page<Knowledge> pageByUserId(Long userId, KnowledgeQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return this.page(Page.of(request.getPageNum(), request.getPageSize()), getQueryWrapper(userId, request));
    }

    @Override
    public QueryWrapper getQueryWrapper(Long userId, KnowledgeQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return QueryWrapper.create()
                .eq("userId", userId)
                .eq("id", request.getId())
                .like("knowledgeName", request.getKnowledgeName())
                .like("description", request.getDescription())
                .orderBy(request.getSortField(), "ascend".equals(request.getSortOrder()));
    }

    @Override
    public KnowledgeVO getKnowledgeVO(Knowledge knowledge) {
        if (knowledge == null) {
            return null;
        }
        KnowledgeVO knowledgeVO = new KnowledgeVO();
        BeanUtil.copyProperties(knowledge, knowledgeVO);
        return knowledgeVO;
    }

    @Override
    public List<KnowledgeVO> getKnowledgeVOList(List<Knowledge> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return new ArrayList<>();
        }
        return knowledges.stream().map(this::getKnowledgeVO).toList();
    }

    @Override
    public void refreshKnowledgeStats(Long userId, Long knowledgeId) {
        Knowledge knowledge = getKnowledgeById(userId, knowledgeId);
        List<com.shenchen.cloudcoldagent.model.entity.Document> documents = documentMapper.selectListByQuery(
                QueryWrapper.create().eq("userId", userId).eq("knowledgeId", knowledgeId)
        );
        knowledge.setDocumentCount(documents.size());
        LocalDateTime lastUploadTime = documents.stream()
                .map(com.shenchen.cloudcoldagent.model.entity.Document::getCreateTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        knowledge.setLastDocumentUploadTime(lastUploadTime);
        this.updateById(knowledge);
    }

    @Override
    public PreparedDocumentIndexResult prepareDocumentIndex(File file, DocumentIndexContext context) throws Exception {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR, "文件不能为空");
        DocumentReadResult readResult = documentReaderFactory.readResult(file.getAbsoluteFile());
        List<KnowledgeDocumentImage> uploadedImages = List.of();
        try {
            uploadedImages = uploadExtractedImages(context, readResult.extractedImages());
            List<EsDocumentChunk> textChunks = buildChunks(readResult.documents(), context, "TEXT");
            return new PreparedDocumentIndexResult(textChunks, uploadedImages);
        } catch (Exception e) {
            cleanupUploadedImages(uploadedImages);
            throw e;
        }
    }

    @Override
    public List<EsDocumentChunk> buildImageDescriptionChunks(List<KnowledgeDocumentImage> images, String documentSource) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<Document> imageDocuments = new ArrayList<>();
        for (KnowledgeDocumentImage image : images) {
            if (image == null || image.getId() == null || image.getDescription() == null || image.getDescription().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("userId", image.getUserId());
            metadata.put("knowledgeId", image.getKnowledgeId());
            metadata.put("documentId", image.getDocumentId());
            metadata.put("parentId", image.getId());
            metadata.put("parentType", "document_image");
            metadata.put("objectName", image.getObjectName());
            metadata.put("contentType", image.getContentType());
            metadata.put("pageNumber", image.getPageNumber());
            metadata.put("chunkType", "IMAGE_DESCRIPTION");
            if (documentSource != null && !documentSource.isBlank()) {
                metadata.put("source", documentSource);
            }
            imageDocuments.add(Document.builder()
                    .id("image-" + image.getId())
                    .text(image.getDescription().trim())
                    .metadata(metadata)
                    .build());
        }
        return buildChunks(imageDocuments, null, "IMAGE_DESCRIPTION");
    }

    @Override
    public void storePreparedChunks(List<EsDocumentChunk> chunks) throws Exception {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        elasticSearchService.bulkIndex(chunks);
        storeService.storeVectorChunks(chunks);
    }

    @Override
    public List<EsDocumentChunk> indexDocument(File file, DocumentIndexContext context) throws Exception {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(context == null, ErrorCode.PARAMS_ERROR, "索引上下文不能为空");
        PreparedDocumentIndexResult preparedResult = prepareDocumentIndex(file, context);
        storePreparedChunks(preparedResult.textChunks());
        return preparedResult.textChunks();
    }

    @Override
    public List<EsDocumentChunk> add(String filePath) throws Exception {
        PreparedDocumentIndexResult preparedResult = prepareDocumentIndex(new File(filePath).getAbsoluteFile(), null);
        storePreparedChunks(preparedResult.textChunks());
        return preparedResult.textChunks();
    }

    @Override
    public List<EsDocumentChunk> update(String filePath) throws Exception {
        String normalizedSource = normalizeSource(filePath);
        deleteBySource(normalizedSource);
        return add(normalizedSource);
    }

    @Override
    public void deleteByIds(List<String> ids) throws Exception {
        elasticSearchService.deleteByIds(ids);
        elasticSearchService.vectorDeleteByIds(ids);
    }

    @Override
    public void deleteByDocumentId(Long documentId) throws Exception {
        if (documentId == null || documentId <= 0) {
            return;
        }
        List<String> chunkIds = collectChunkIdsByMetadata(Map.of("documentId", documentId));

        if (!chunkIds.isEmpty()) {
            deleteByIds(chunkIds);
        }

        elasticSearchService.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteBySource(String source) throws Exception {
        String normalizedSource = normalizeSource(source);
        List<String> chunkIds = collectChunkIdsByMetadata(Map.of("source", normalizedSource));
        if (!chunkIds.isEmpty()) {
            deleteByIds(chunkIds);
        }
        elasticSearchService.deleteBySource(normalizedSource);
    }

    @Override
    public List<EsDocumentChunk> scalarSearch(String query) throws Exception {
        return scalarSearch(query, DEFAULT_SCALAR_TOP_K, false);
    }

    @Override
    public List<EsDocumentChunk> scalarSearch(String query, int size, boolean useSmartAnalyzer) throws Exception {
        return elasticSearchService.searchByKeyword(query, size, useSmartAnalyzer);
    }

    @Override
    public List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return scalarSearch(userId, knowledgeId, query, DEFAULT_SCALAR_TOP_K, false);
    }

    @Override
    public List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query, int size,
                                              boolean useSmartAnalyzer) throws Exception {
        return elasticSearchService.searchByKeyword(query, size, useSmartAnalyzer,
                buildKnowledgeScopeMetadata(userId, knowledgeId));
    }

    @Override
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception {
        return metadataSearch(metadataFilters, DEFAULT_SCALAR_TOP_K);
    }

    @Override
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception {
        return elasticSearchService.searchByMetadata(metadataFilters, size);
    }

    @Override
    public List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters)
            throws Exception {
        return metadataSearch(userId, knowledgeId, metadataFilters, DEFAULT_SCALAR_TOP_K);
    }

    @Override
    public List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters,
                                                int size) throws Exception {
        return elasticSearchService.searchByMetadata(
                mergeMetadataFilters(buildKnowledgeScopeMetadata(userId, knowledgeId), metadataFilters), size);
    }

    @Override
    public List<EsDocumentChunk> vectorSearch(String query) throws Exception {
        return vectorSearch(query, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD, null);
    }

    @Override
    public List<EsDocumentChunk> vectorSearch(String query, int topK, double similarityThreshold, String filterExpression)
            throws Exception {
        return elasticSearchService.similaritySearch(query, topK, similarityThreshold, filterExpression).stream()
                .map(this::toChunk)
                .toList();
    }

    @Override
    public List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return vectorSearch(userId, knowledgeId, query, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD);
    }

    @Override
    public List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query, int topK,
                                              double similarityThreshold) throws Exception {
        return elasticSearchService.similaritySearch(query, topK, similarityThreshold,
                        buildKnowledgeScopeMetadata(userId, knowledgeId)).stream()
                .map(this::toChunk)
                .toList();
    }

    @Override
    public List<EsDocumentChunk> hybridSearch(String query) throws Exception {
        return hybridSearch(query, DEFAULT_SCALAR_TOP_K, false, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD, null);
    }

    @Override
    public List<EsDocumentChunk> hybridSearch(String query, int keywordSize, boolean useSmartAnalyzer, int vectorTopK,
                                              double similarityThreshold, String filterExpression) throws Exception {
        List<EsDocumentChunk> scalarResults = scalarSearch(query, keywordSize, useSmartAnalyzer);
        List<Document> vectorDocuments = elasticSearchService.similaritySearch(query, vectorTopK, similarityThreshold, filterExpression);
        return rrfFusion(vectorDocuments, scalarResults, Math.max(keywordSize, vectorTopK));
    }

    @Override
    public List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return hybridSearch(userId, knowledgeId, query, DEFAULT_SCALAR_TOP_K, false, DEFAULT_VECTOR_TOP_K,
                DEFAULT_ACCEPT_ALL_THRESHOLD);
    }

    @Override
    public List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query, int keywordSize,
                                              boolean useSmartAnalyzer, int vectorTopK,
                                              double similarityThreshold) throws Exception {
        Map<String, Object> scopeMetadata = buildKnowledgeScopeMetadata(userId, knowledgeId);
        List<EsDocumentChunk> scalarResults = elasticSearchService.searchByKeyword(query, keywordSize, useSmartAnalyzer,
                scopeMetadata);
        List<Document> vectorDocuments = elasticSearchService.similaritySearch(query, vectorTopK, similarityThreshold,
                scopeMetadata);
        return rrfFusion(vectorDocuments, scalarResults, Math.max(keywordSize, vectorTopK));
    }

    private List<EsDocumentChunk> buildChunks(List<Document> documents, DocumentIndexContext context, String defaultChunkType) {
        List<Document> workingDocuments = DocumentCleaner.cleanDocuments(documents);
        if (context != null) {
            workingDocuments = enrichDocuments(workingDocuments, context);
        }

        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> splitDocuments = splitter.apply(workingDocuments);

        List<EsDocumentChunk> chunks = new ArrayList<>(splitDocuments.size());
        for (Document document : splitDocuments) {
            EsDocumentChunk chunk = new EsDocumentChunk();
            chunk.setId(document.getId());
            chunk.setContent(document.getText());
            Map<String, Object> metadata = document.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(document.getMetadata());
            metadata.putIfAbsent("chunkId", document.getId());
            if (defaultChunkType != null && !defaultChunkType.isBlank()) {
                metadata.putIfAbsent("chunkType", defaultChunkType);
            }
            chunk.setMetadata(metadata);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<KnowledgeDocumentImage> uploadExtractedImages(DocumentIndexContext context,
                                                               List<ExtractedDocumentImage> extractedImages) throws Exception {
        if (extractedImages == null || extractedImages.isEmpty()) {
            return List.of();
        }
        if (context == null) {
            return List.of();
        }

        List<KnowledgeDocumentImage> uploadedImages = new ArrayList<>();
        try {
            for (ExtractedDocumentImage extractedImage : extractedImages) {
                if (extractedImage == null || extractedImage.bytes() == null || extractedImage.bytes().length == 0) {
                    continue;
                }
                String contentType = extractedImage.contentType() == null || extractedImage.contentType().isBlank()
                        ? "image/png"
                        : extractedImage.contentType().trim();
                String objectName = buildDocumentImageObjectName(context, extractedImage.imageIndex(), contentType);
                String imageUrl = minioService.uploadFile(objectName, extractedImage.bytes(), contentType);
                uploadedImages.add(KnowledgeDocumentImage.builder()
                        .id(IdUtil.getSnowflakeNextId())
                        .userId(context.userId())
                        .knowledgeId(context.knowledgeId())
                        .documentId(context.documentId())
                        .imageIndex(extractedImage.imageIndex())
                        .objectName(objectName)
                        .imageUrl(imageUrl)
                        .contentType(contentType)
                        .fileSize((long) extractedImage.bytes().length)
                        .pageNumber(extractedImage.pageNumber())
                        .description(extractedImage.description())
                        .build());
            }
            return uploadedImages;
        } catch (Exception e) {
            cleanupUploadedImages(uploadedImages);
            throw e;
        }
    }

    private String buildDocumentImageObjectName(DocumentIndexContext context, Integer imageIndex, String contentType) {
        String extension = resolveImageExtension(contentType);
        int safeImageIndex = imageIndex == null || imageIndex < 0 ? 0 : imageIndex;
        return "knowledge/" + context.knowledgeId()
                + "/document/" + context.documentId()
                + "/images/" + safeImageIndex + "-" + UUID.randomUUID() + extension;
    }

    private String resolveImageExtension(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return ".bin";
        }
        String normalized = contentType.trim().toLowerCase();
        return switch (normalized) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    private void cleanupUploadedImages(List<KnowledgeDocumentImage> uploadedImages) {
        if (uploadedImages == null || uploadedImages.isEmpty()) {
            return;
        }
        for (KnowledgeDocumentImage uploadedImage : uploadedImages) {
            if (uploadedImage == null || uploadedImage.getObjectName() == null || uploadedImage.getObjectName().isBlank()) {
                continue;
            }
            try {
                minioService.deleteFile(uploadedImage.getObjectName());
            } catch (Exception cleanupException) {
                log.warn("清理已上传图片失败，documentId={}, objectName={}, message={}",
                        uploadedImage.getDocumentId(),
                        uploadedImage.getObjectName(),
                        cleanupException.getMessage());
            }
        }
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        if (source.contains("://")) {
            return source.trim();
        }
        return new File(source).getAbsoluteFile().getAbsolutePath();
    }

    private String buildSourceFilterExpression(String source) {
        return "source == '" + source.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String buildNumericFilterExpression(String field, Number value) {
        return field + " == " + value;
    }

    private List<String> collectChunkIdsByMetadata(Map<String, Object> metadataFilters) throws Exception {
        List<EsDocumentChunk> chunks = elasticSearchService.searchByMetadata(metadataFilters, 10_000);
        return chunks.stream()
                .map(EsDocumentChunk::getId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
    }

    private EsDocumentChunk toChunk(Document document) {
        EsDocumentChunk chunk = new EsDocumentChunk();
        chunk.setId(document.getId());
        chunk.setContent(document.getText());
        Map<String, Object> metadata = document.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(document.getMetadata());
        if (document.getScore() != null) {
            metadata.put("vector_score", document.getScore());
        }
        metadata.putIfAbsent("chunkId", document.getId());
        chunk.setMetadata(metadata);
        return chunk;
    }

    private List<Document> enrichDocuments(List<Document> documents, DocumentIndexContext context) {
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String sourceDocumentId = context.documentId() + "#source-" + i;
            Map<String, Object> metadata = document.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(document.getMetadata());
            metadata.put("userId", context.userId());
            metadata.put("knowledgeId", context.knowledgeId());
            metadata.put("documentId", context.documentId());
            metadata.put("documentName", context.documentName());
            metadata.put("objectName", context.objectName());
            metadata.put("source", context.documentSource());
            metadata.put("fileName", context.documentName());
            metadata.put("fileType", context.fileType());
            metadata.put("contentType", context.contentType());
            metadata.put("fileSize", context.fileSize());
            metadata.put("source_document_id", sourceDocumentId);
            metadata.put("chunkType", "TEXT");
            result.add(document.mutate()
                    .id(sourceDocumentId)
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    private Map<String, Object> buildKnowledgeScopeMetadata(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        getKnowledgeById(userId, knowledgeId);

        Map<String, Object> scopeMetadata = new LinkedHashMap<>();
        scopeMetadata.put("userId", userId);
        scopeMetadata.put("knowledgeId", knowledgeId);
        return scopeMetadata;
    }

    private Map<String, Object> mergeMetadataFilters(Map<String, Object> fixedFilters, Map<String, Object> dynamicFilters) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fixedFilters != null) {
            result.putAll(fixedFilters);
        }
        if (dynamicFilters != null) {
            dynamicFilters.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    result.putIfAbsent(key, value);
                }
            });
        }
        return result;
    }

    private void deleteDocumentIndex(com.shenchen.cloudcoldagent.model.entity.Document document) throws Exception {
        if (document == null) {
            return;
        }
        try {
            deleteByDocumentId(document.getId());
        } catch (Exception primaryDeleteException) {
            if (document.getDocumentSource() == null || document.getDocumentSource().isBlank()) {
                throw primaryDeleteException;
            }
            log.warn("按 documentId 删除文档索引失败，回退按 source 删除。documentId={}, source={}",
                    document.getId(), document.getDocumentSource(), primaryDeleteException);
            deleteBySource(document.getDocumentSource());
        }
        deleteDocumentImages(document.getId());
        if (document.getObjectName() != null && !document.getObjectName().isBlank()) {
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

    /**
     * RRF 算法融合向量检索和关键词检索结果。
     * 公式：RRF Score = Σ(1/(k + rank_i))，其中 k 为常数（通常取60），rank_i 为文档在第i个检索结果中的排名。
     */
    private List<EsDocumentChunk> rrfFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, String> idToChunkId = new HashMap<>();
        Map<String, Integer> scalarRanks = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            String docId = doc.getId();
            if (docId == null || docId.isBlank()) {
                continue;
            }
            String chunkId = extractChunkId(doc.getMetadata(), docId);
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;
            double score = 1.0d / (HYBRID_RRF_K + rank);
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0d) + score);
            vectorRanks.put(docId, rank);
        }

        for (int i = 0; i < keywordDocs.size(); i++) {
            EsDocumentChunk doc = keywordDocs.get(i);
            String docId = doc.getId();
            if (docId == null || docId.isBlank()) {
                continue;
            }
            String chunkId = extractChunkId(doc.getMetadata(), docId);
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;
            double score = 1.0d / (HYBRID_RRF_K + rank);
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0d) + score);
            scalarRanks.put(docId, rank);
        }

        List<String> sortedDocIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(topK)
                .toList();

        String scoresLog = sortedDocIds.stream()
                .map(docId -> {
                    String chunkId = idToChunkId.getOrDefault(docId, "unknown");
                    double score = rrfScores.getOrDefault(docId, 0.0d);
                    return String.format("chunkId: %s, RRF Score: %.4f", chunkId, score);
                })
                .collect(Collectors.joining("; "));
        log.info("RRF融合后top{}结果：{}", topK, scoresLog);

        Map<String, EsDocumentChunk> idToChunk = new LinkedHashMap<>();
        vectorDocs.forEach(doc -> idToChunk.putIfAbsent(doc.getId(), toChunk(doc)));
        keywordDocs.forEach(doc -> idToChunk.putIfAbsent(doc.getId(), copyChunk(doc)));

        return sortedDocIds.stream()
                .map(idToChunk::get)
                .filter(Objects::nonNull)
                .map(chunk -> enrichHybridChunk(chunk, rrfScores, scalarRanks, vectorRanks))
                .toList();
    }

    private EsDocumentChunk enrichHybridChunk(EsDocumentChunk chunk,
                                              Map<String, Double> rrfScores,
                                              Map<String, Integer> scalarRanks,
                                              Map<String, Integer> vectorRanks) {
        EsDocumentChunk copied = copyChunk(chunk);
        Map<String, Object> metadata = copied.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(copied.getMetadata());
        metadata.put("hybrid_score", rrfScores.getOrDefault(copied.getId(), 0.0d));
        if (scalarRanks.containsKey(copied.getId())) {
            metadata.put("scalar_rank", scalarRanks.get(copied.getId()));
        }
        if (vectorRanks.containsKey(copied.getId())) {
            metadata.put("vector_rank", vectorRanks.get(copied.getId()));
        }
        copied.setMetadata(metadata);
        return copied;
    }

    private EsDocumentChunk copyChunk(EsDocumentChunk source) {
        EsDocumentChunk copied = new EsDocumentChunk();
        copied.setId(source.getId());
        copied.setContent(source.getContent());
        copied.setMetadata(source.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getMetadata()));
        return copied;
    }

    private String extractChunkId(Map<String, Object> metadata, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object chunkId = metadata.get("chunkId");
        return chunkId == null ? fallback : chunkId.toString();
    }
}
