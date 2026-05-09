package com.shenchen.cloudcoldagent.service.knowledge.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.bean.BeanUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.document.extract.cleaner.DocumentCleaner;
import com.shenchen.cloudcoldagent.document.extract.reader.DocumentReaderFactory;
import com.shenchen.cloudcoldagent.document.load.store.StoreService;
import com.shenchen.cloudcoldagent.constant.KnowledgeChunkConstant;
import com.shenchen.cloudcoldagent.document.transform.splitter.OverlapParagraphTextSplitter;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.mapper.knowledge.DocumentMapper;
import com.shenchen.cloudcoldagent.mapper.knowledge.KnowledgeMapper;
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
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.storage.ElasticSearchService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.storage.MinioService;
import com.shenchen.cloudcoldagent.utils.DeleteExceptionUtils;
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

/**
 * 知识库服务实现，负责知识库管理、文档切分入库、图片描述 chunk 构建以及多路检索融合。
 */
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

    /**
     * 注入知识库主链路所需的依赖服务。
     *
     * @param documentReaderFactory 文档读取工厂。
     * @param elasticSearchService Elasticsearch 检索服务。
     * @param storeService 向量存储服务。
     * @param documentMapper 文档 mapper。
     * @param minioService MinIO 文件服务。
     * @param knowledgeDocumentImageService 文档图片业务服务。
     */
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

    /**
     * 创建一个新的知识库。
     *
     * @param userId 当前用户 id。
     * @param request 创建请求体。
     * @return 新知识库 id。
     */
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

    /**
     * 更新知识库名称和描述。
     *
     * @param userId 当前用户 id。
     * @param request 更新请求体。
     * @return 是否更新成功。
     */
    @Override
    public boolean updateKnowledge(Long userId, KnowledgeUpdateRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);

        Knowledge existing = getKnowledgeById(userId, request.getId());
        existing.setKnowledgeName(request.getKnowledgeName());
        existing.setDescription(request.getDescription());
        return this.updateById(existing);
    }

    /**
     * 删除知识库及其下属文档、索引和图片资源。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @return 是否删除成功。
     */
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

    /**
     * 查询当前用户可访问的单个知识库实体。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @return 知识库实体。
     */
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

    /**
     * 分页查询当前用户的知识库列表。
     *
     * @param userId 当前用户 id。
     * @param request 分页查询条件。
     * @return 知识库分页结果。
     */
    @Override
    public Page<Knowledge> pageByUserId(Long userId, KnowledgeQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return this.page(Page.of(request.getPageNum(), request.getPageSize()), getQueryWrapper(userId, request));
    }

    /**
     * 根据查询条件构建知识库分页查询包装器。
     *
     * @param userId 当前用户 id。
     * @param request 查询条件。
     * @return MyBatis-Flex 查询包装器。
     */
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

    /**
     * 将知识库实体转换成对外返回的 VO。
     *
     * @param knowledge 知识库实体。
     * @return 知识库 VO。
     */
    @Override
    public KnowledgeVO getKnowledgeVO(Knowledge knowledge) {
        if (knowledge == null) {
            return null;
        }
        KnowledgeVO knowledgeVO = new KnowledgeVO();
        BeanUtil.copyProperties(knowledge, knowledgeVO);
        return knowledgeVO;
    }

    /**
     * 批量将知识库实体列表转换成 VO 列表。
     *
     * @param knowledges 知识库实体列表。
     * @return 知识库 VO 列表。
     */
    @Override
    public List<KnowledgeVO> getKnowledgeVOList(List<Knowledge> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return new ArrayList<>();
        }
        return knowledges.stream().map(this::getKnowledgeVO).toList();
    }

    /**
     * 刷新知识库中的文档数量和最近上传时间统计。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     */
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

    /**
     * 读取文档、抽取图片并生成入库前的正文 chunk 与图片记录。
     *
     * @param file 待处理文件。
     * @param context 文档索引上下文。
     * @return 文档索引准备结果。
     * @throws Exception 读取文档或上传图片失败时抛出。
     */
    @Override
    public PreparedDocumentIndexResult prepareDocumentIndex(File file, DocumentIndexContext context) throws Exception {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR, "文件不能为空");
        DocumentReadResult readResult = documentReaderFactory.readResult(file.getAbsoluteFile());
        List<KnowledgeDocumentImage> uploadedImages = List.of();
        try {
            uploadedImages = uploadExtractedImages(context, readResult.extractedImages());
            List<EsDocumentChunk> textChunks = buildChunks(readResult.documents(), context, KnowledgeChunkConstant.CHUNK_TYPE_TEXT);
            return new PreparedDocumentIndexResult(textChunks, uploadedImages);
        } catch (Exception e) {
            cleanupUploadedImages(uploadedImages);
            throw e;
        }
    }

    /**
     * 将已保存的文档图片记录转换成图片描述 chunk。
     *
     * @param images 文档图片列表。
     * @param documentSource 文档来源标识。
     * @return 图片描述 chunk 列表。
     */
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
            metadata.put(KnowledgeChunkConstant.META_USER_ID, image.getUserId());
            metadata.put(KnowledgeChunkConstant.META_KNOWLEDGE_ID, image.getKnowledgeId());
            metadata.put(KnowledgeChunkConstant.META_DOCUMENT_ID, image.getDocumentId());
            metadata.put(KnowledgeChunkConstant.META_PARENT_ID, image.getId());
            metadata.put(KnowledgeChunkConstant.META_PARENT_TYPE, KnowledgeChunkConstant.PARENT_TYPE_DOCUMENT_IMAGE);
            metadata.put(KnowledgeChunkConstant.META_OBJECT_NAME, image.getObjectName());
            metadata.put(KnowledgeChunkConstant.META_CONTENT_TYPE, image.getContentType());
            metadata.put(KnowledgeChunkConstant.META_PAGE_NUMBER, image.getPageNumber());
            metadata.put(KnowledgeChunkConstant.META_CHUNK_TYPE, KnowledgeChunkConstant.CHUNK_TYPE_IMAGE_DESCRIPTION);
            if (documentSource != null && !documentSource.isBlank()) {
                metadata.put(KnowledgeChunkConstant.META_SOURCE, documentSource);
            }
            imageDocuments.add(Document.builder()
                    .id("image-" + image.getId())
                    .text(image.getDescription().trim())
                    .metadata(metadata)
                    .build());
        }
        return buildChunks(imageDocuments, null, KnowledgeChunkConstant.CHUNK_TYPE_IMAGE_DESCRIPTION);
    }

    /**
     * 将准备好的 chunk 同步写入关键词索引和向量索引。
     *
     * @param chunks 待入库的 chunk 列表。
     * @throws Exception 写入任一索引失败时抛出。
     */
    @Override
    public void storePreparedChunks(List<EsDocumentChunk> chunks) throws Exception {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        elasticSearchService.bulkIndex(chunks);
        storeService.storeVectorChunks(chunks);
    }

    /**
     * 为单个文件执行完整的解析并入库流程。
     *
     * @param file 待处理文件。
     * @param context 文档索引上下文。
     * @return 写入完成后的正文 chunk 列表。
     * @throws Exception 解析或入库失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> indexDocument(File file, DocumentIndexContext context) throws Exception {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(context == null, ErrorCode.PARAMS_ERROR, "索引上下文不能为空");
        PreparedDocumentIndexResult preparedResult = prepareDocumentIndex(file, context);
        storePreparedChunks(preparedResult.textChunks());
        return preparedResult.textChunks();
    }

    /**
     * 以本地文件路径为入口执行文档入库。
     *
     * @param filePath 本地文件路径。
     * @return 入库后的正文 chunk 列表。
     * @throws Exception 解析或入库失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> add(String filePath) throws Exception {
        PreparedDocumentIndexResult preparedResult = prepareDocumentIndex(new File(filePath).getAbsoluteFile(), null);
        storePreparedChunks(preparedResult.textChunks());
        return preparedResult.textChunks();
    }

    /**
     * 先删除同 source 的旧索引，再重新执行入库。
     *
     * @param filePath 本地文件路径或 source。
     * @return 重新入库后的正文 chunk 列表。
     * @throws Exception 删除旧索引或重新入库失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> update(String filePath) throws Exception {
        String normalizedSource = normalizeSource(filePath);
        deleteBySource(normalizedSource);
        return add(normalizedSource);
    }

    /**
     * 删除 `delete By Ids` 对应内容。
     *
     * @param ids ids 参数。
     * @throws Exception 异常信息。
     */
    @Override
    public void deleteByIds(List<String> ids) throws Exception {
        elasticSearchService.deleteByIds(ids);
        elasticSearchService.vectorDeleteByIds(ids);
    }

    /**
     * 删除 `delete By Document Id` 对应内容。
     *
     * @param documentId documentId 参数。
     * @throws Exception 异常信息。
     */
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

    /**
     * 删除 `delete By Source` 对应内容。
     *
     * @param source source 参数。
     * @throws Exception 异常信息。
     */
    @Override
    public void deleteBySource(String source) throws Exception {
        String normalizedSource = normalizeSource(source);
        List<String> chunkIds = collectChunkIdsByMetadata(Map.of("source", normalizedSource));
        if (!chunkIds.isEmpty()) {
            deleteByIds(chunkIds);
        }
        elasticSearchService.deleteBySource(normalizedSource);
    }

    /**
     * 执行全局关键词检索。
     *
     * @param query 查询文本。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> scalarSearch(String query) throws Exception {
        return scalarSearch(query, DEFAULT_SCALAR_TOP_K, false);
    }

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> scalarSearch(String query, int size, boolean useSmartAnalyzer) throws Exception {
        return elasticSearchService.searchByKeyword(query, size, useSmartAnalyzer);
    }

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return scalarSearch(userId, knowledgeId, query, DEFAULT_SCALAR_TOP_K, false);
    }

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query, int size,
                                              boolean useSmartAnalyzer) throws Exception {
        return elasticSearchService.searchByKeyword(query, size, useSmartAnalyzer,
                buildKnowledgeScopeMetadata(userId, knowledgeId));
    }

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception {
        return metadataSearch(metadataFilters, DEFAULT_SCALAR_TOP_K);
    }

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception {
        return elasticSearchService.searchByMetadata(metadataFilters, size);
    }

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters)
            throws Exception {
        return metadataSearch(userId, knowledgeId, metadataFilters, DEFAULT_SCALAR_TOP_K);
    }

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param metadataFilters metadataFilters 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters,
                                                int size) throws Exception {
        return elasticSearchService.searchByMetadata(
                mergeMetadataFilters(buildKnowledgeScopeMetadata(userId, knowledgeId), metadataFilters), size);
    }

    /**
     * 执行全局向量相似度检索。
     *
     * @param query 查询文本。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> vectorSearch(String query) throws Exception {
        return vectorSearch(query, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD, null);
    }

    /**
     * 处理 `vector Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param topK topK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @param filterExpression filterExpression 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> vectorSearch(String query, int topK, double similarityThreshold, String filterExpression)
            throws Exception {
        return elasticSearchService.similaritySearch(query, topK, similarityThreshold, filterExpression).stream()
                .map(this::toChunk)
                .toList();
    }

    /**
     * 处理 `vector Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Override
    public List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return vectorSearch(userId, knowledgeId, query, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD);
    }

    /**
     * 在指定知识库范围内执行向量相似度检索。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @param query 查询文本。
     * @param topK 召回数量上限。
     * @param similarityThreshold 相似度阈值。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query, int topK,
                                              double similarityThreshold) throws Exception {
        return elasticSearchService.similaritySearch(query, topK, similarityThreshold,
                        buildKnowledgeScopeMetadata(userId, knowledgeId)).stream()
                .map(this::toChunk)
                .toList();
    }

    /**
     * 执行全局混合检索，并使用 RRF 融合关键词与向量结果。
     *
     * @param query 查询文本。
     * @return 融合后的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> hybridSearch(String query) throws Exception {
        return hybridSearch(query, DEFAULT_SCALAR_TOP_K, false, DEFAULT_VECTOR_TOP_K, DEFAULT_ACCEPT_ALL_THRESHOLD, null);
    }

    /**
     * 在给定参数下执行全局混合检索。
     *
     * @param query 查询文本。
     * @param keywordSize 关键词检索返回数量。
     * @param useSmartAnalyzer 是否使用智能分词器。
     * @param vectorTopK 向量检索返回数量。
     * @param similarityThreshold 向量相似度阈值。
     * @param filterExpression 向量检索过滤表达式。
     * @return 融合后的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> hybridSearch(String query, int keywordSize, boolean useSmartAnalyzer, int vectorTopK,
                                              double similarityThreshold, String filterExpression) throws Exception {
        List<EsDocumentChunk> scalarResults = scalarSearch(query, keywordSize, useSmartAnalyzer);
        List<Document> vectorDocuments = elasticSearchService.similaritySearch(query, vectorTopK, similarityThreshold, filterExpression);
        return rrfFusion(vectorDocuments, scalarResults, Math.max(keywordSize, vectorTopK));
    }

    /**
     * 在指定知识库范围内执行默认参数的混合检索。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @param query 查询文本。
     * @return 融合后的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query) throws Exception {
        return hybridSearch(userId, knowledgeId, query, DEFAULT_SCALAR_TOP_K, false, DEFAULT_VECTOR_TOP_K,
                DEFAULT_ACCEPT_ALL_THRESHOLD);
    }

    /**
     * 在指定知识库范围内执行自定义参数的混合检索。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @param query 查询文本。
     * @param keywordSize 关键词检索返回数量。
     * @param useSmartAnalyzer 是否使用智能分词器。
     * @param vectorTopK 向量检索返回数量。
     * @param similarityThreshold 向量相似度阈值。
     * @return 融合后的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
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

    /**
     * 将 Spring AI Document 列表切分并转换成 ES chunk 实体。
     *
     * @param documents 原始文档列表。
     * @param context 文档索引上下文。
     * @param defaultChunkType 默认 chunk 类型。
     * @return 切分后的 chunk 列表。
     */
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
            metadata.putIfAbsent(KnowledgeChunkConstant.META_CHUNK_ID, document.getId());
            if (defaultChunkType != null && !defaultChunkType.isBlank()) {
                metadata.putIfAbsent(KnowledgeChunkConstant.META_CHUNK_TYPE, defaultChunkType);
            }
            chunk.setMetadata(metadata);
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 上传解析阶段抽取出的图片，并组装成图片记录实体。
     *
     * @param context 文档索引上下文。
     * @param extractedImages 解析阶段抽取出的图片。
     * @return 已上传的图片记录列表。
     * @throws Exception 上传失败时抛出。
     */
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

    /**
     * 构建文档图片在对象存储中的对象名。
     *
     * @param context 文档索引上下文。
     * @param imageIndex 图片序号。
     * @param contentType 图片内容类型。
     * @return 对象名。
     */
    private String buildDocumentImageObjectName(DocumentIndexContext context, Integer imageIndex, String contentType) {
        String extension = resolveImageExtension(contentType);
        int safeImageIndex = imageIndex == null || imageIndex < 0 ? 0 : imageIndex;
        return KnowledgeChunkConstant.OBJECT_PREFIX_KNOWLEDGE + context.knowledgeId()
                + "/document/" + context.documentId()
                + "/images/" + safeImageIndex + "-" + UUID.randomUUID() + extension;
    }

    /**
     * 根据图片 contentType 推导对象存储扩展名。
     *
     * @param contentType 图片内容类型。
     * @return 对应的文件扩展名。
     */
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

    /**
     * 清理已经上传到对象存储中的文档图片。
     *
     * @param uploadedImages 已上传的图片记录列表。
     */
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

    /**
     * 规范化文档 source 标识，兼容本地路径和带协议地址。
     *
     * @param source 原始 source。
     * @return 规范化后的 source。
     */
    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        if (source.contains("://")) {
            return source.trim();
        }
        return new File(source).getAbsoluteFile().getAbsolutePath();
    }

    /**
     * 构建基于 source 的向量检索过滤表达式。
     *
     * @param source 规范化后的 source。
     * @return 过滤表达式。
     */
    private String buildSourceFilterExpression(String source) {
        return "source == '" + source.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * 构建针对数值字段的向量检索过滤表达式。
     *
     * @param field 字段名。
     * @param value 字段值。
     * @return 过滤表达式。
     */
    private String buildNumericFilterExpression(String field, Number value) {
        return field + " == " + value;
    }

    /**
     * 根据元数据条件收集关键词索引中的 chunk id。
     *
     * @param metadataFilters 元数据过滤条件。
     * @return 命中的 chunk id 列表。
     * @throws Exception 检索失败时抛出。
     */
    private List<String> collectChunkIdsByMetadata(Map<String, Object> metadataFilters) throws Exception {
        List<EsDocumentChunk> chunks = elasticSearchService.searchByMetadata(metadataFilters, 10_000);
        return chunks.stream()
                .map(EsDocumentChunk::getId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
    }

    /**
     * 将向量检索返回的 Spring AI Document 转换成系统内部的 chunk 结构。
     *
     * @param document 向量检索命中的文档对象。
     * @return chunk 实体。
     */
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
        metadata.putIfAbsent(KnowledgeChunkConstant.META_CHUNK_ID, document.getId());
        chunk.setMetadata(metadata);
        return chunk;
    }

    /**
     * 为原始文档列表补齐入库所需的知识库、文档和文件元数据。
     *
     * @param documents 原始文档列表。
     * @param context 文档索引上下文。
     * @return 补齐元数据后的文档列表。
     */
    private List<Document> enrichDocuments(List<Document> documents, DocumentIndexContext context) {
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String sourceDocumentId = context.documentId() + "#source-" + i;
            Map<String, Object> metadata = document.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(document.getMetadata());
            metadata.put(KnowledgeChunkConstant.META_USER_ID, context.userId());
            metadata.put(KnowledgeChunkConstant.META_KNOWLEDGE_ID, context.knowledgeId());
            metadata.put(KnowledgeChunkConstant.META_DOCUMENT_ID, context.documentId());
            metadata.put(KnowledgeChunkConstant.META_DOCUMENT_NAME, context.documentName());
            metadata.put(KnowledgeChunkConstant.META_OBJECT_NAME, context.objectName());
            metadata.put(KnowledgeChunkConstant.META_SOURCE, context.documentSource());
            metadata.put(KnowledgeChunkConstant.META_FILE_NAME, context.documentName());
            metadata.put(KnowledgeChunkConstant.META_FILE_TYPE, context.fileType());
            metadata.put(KnowledgeChunkConstant.META_CONTENT_TYPE, context.contentType());
            metadata.put(KnowledgeChunkConstant.META_FILE_SIZE, context.fileSize());
            metadata.put(KnowledgeChunkConstant.META_SOURCE_DOCUMENT_ID, sourceDocumentId);
            metadata.put(KnowledgeChunkConstant.META_CHUNK_TYPE, KnowledgeChunkConstant.CHUNK_TYPE_TEXT);
            result.add(document.mutate()
                    .id(sourceDocumentId)
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    /**
     * 构建用于限定检索范围的知识库元数据条件。
     *
     * @param userId 当前用户 id。
     * @param knowledgeId 知识库 id。
     * @return 范围元数据映射。
     */
    private Map<String, Object> buildKnowledgeScopeMetadata(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        getKnowledgeById(userId, knowledgeId);

        Map<String, Object> scopeMetadata = new LinkedHashMap<>();
        scopeMetadata.put(KnowledgeChunkConstant.META_USER_ID, userId);
        scopeMetadata.put(KnowledgeChunkConstant.META_KNOWLEDGE_ID, knowledgeId);
        return scopeMetadata;
    }

    /**
     * 合并固定的知识库范围条件和动态查询条件。
     *
     * @param fixedFilters 固定范围条件。
     * @param dynamicFilters 动态过滤条件。
     * @return 合并后的元数据过滤条件。
     */
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

    /**
     * 删除 `delete Document Index` 对应内容。
     *
     * @param document document 参数。
     * @throws Exception 异常信息。
     */
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

    /**
     * 删除某个文档关联的全部图片对象和图片记录。
     *
     * @param documentId 文档 id。
     * @throws Exception 删除图片对象失败且不可忽略时抛出。
     */
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

    /**
     * 判断删除阶段出现的异常是否属于对象已不存在这类可忽略场景。
     *
     * @param exception 删除时抛出的异常。
     * @return 可忽略时返回 true。
     */
    private boolean isIgnorableDeleteException(Exception exception) {
        return DeleteExceptionUtils.isIgnorableDeleteException(exception);
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

    /**
     * 为融合后的 chunk 补齐 hybrid score、关键词排名和向量排名信息。
     *
     * @param chunk 原始 chunk。
     * @param rrfScores RRF 融合分值映射。
     * @param scalarRanks 关键词检索排名映射。
     * @param vectorRanks 向量检索排名映射。
     * @return 补齐融合元数据后的 chunk。
     */
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

    /**
     * 复制一份 chunk，避免直接修改原始对象。
     *
     * @param source 原始 chunk。
     * @return 拷贝后的 chunk。
     */
    private EsDocumentChunk copyChunk(EsDocumentChunk source) {
        EsDocumentChunk copied = new EsDocumentChunk();
        copied.setId(source.getId());
        copied.setContent(source.getContent());
        copied.setMetadata(source.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getMetadata()));
        return copied;
    }

    /**
     * 从元数据中提取 chunkId，若不存在则回退到文档 id。
     *
     * @param metadata 文档元数据。
     * @param fallback 回退 id。
     * @return 最终使用的 chunk id。
     */
    private String extractChunkId(Map<String, Object> metadata, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object chunkId = metadata.get("chunkId");
        return chunkId == null ? fallback : chunkId.toString();
    }
}
