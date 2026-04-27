package com.shenchen.cloudcoldagent.service.impl;

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
import com.shenchen.cloudcoldagent.model.vo.KnowledgeVO;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
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
import java.util.stream.Collectors;

import static org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

@Service
@Slf4j
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, Knowledge> implements KnowledgeService {

    private static final int DEFAULT_CHUNK_SIZE = 200;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;
    private static final int DEFAULT_SCALAR_TOP_K = 5;
    private static final int DEFAULT_VECTOR_TOP_K = 5;
    private static final double DEFAULT_ACCEPT_ALL_THRESHOLD = SIMILARITY_THRESHOLD_ACCEPT_ALL;
    private static final int HYBRID_RRF_K = 60;

    private final DocumentReaderFactory documentReaderFactory;
    private final ElasticSearchService elasticSearchService;
    private final StoreService storeService;
    private final DocumentMapper documentMapper;

    public KnowledgeServiceImpl(DocumentReaderFactory documentReaderFactory,
                                ElasticSearchService elasticSearchService,
                                StoreService storeService,
                                DocumentMapper documentMapper) {
        this.documentReaderFactory = documentReaderFactory;
        this.elasticSearchService = elasticSearchService;
        this.storeService = storeService;
        this.documentMapper = documentMapper;
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
            if (document.getDocumentSource() != null && !document.getDocumentSource().isBlank()) {
                try {
                    deleteBySource(document.getDocumentSource());
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除知识库关联的索引内容失败: " + document.getDocumentName());
                }
            }
        }
        documentMapper.deleteByQuery(QueryWrapper.create().eq("knowledgeId", knowledgeId).eq("userId", userId));
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
    public List<EsDocumentChunk> add(String filePath) throws Exception {
        List<EsDocumentChunk> chunks = buildChunks(filePath);
        elasticSearchService.bulkIndex(chunks);
        storeService.storeVectorChunks(chunks);
        return chunks;
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
    public void deleteBySource(String source) throws Exception {
        String normalizedSource = normalizeSource(source);
        elasticSearchService.deleteBySource(normalizedSource);
        elasticSearchService.vectorDeleteByFilter(buildSourceFilterExpression(normalizedSource));
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
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception {
        return metadataSearch(metadataFilters, DEFAULT_SCALAR_TOP_K);
    }

    @Override
    public List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception {
        return elasticSearchService.searchByMetadata(metadataFilters, size);
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

    private List<EsDocumentChunk> buildChunks(String filePath) throws Exception {
        File file = new File(filePath).getAbsoluteFile();
        List<Document> documents = documentReaderFactory.read(file);
        documents = DocumentCleaner.cleanDocuments(documents);

        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> splitDocuments = splitter.apply(documents);

        List<EsDocumentChunk> chunks = new ArrayList<>(splitDocuments.size());
        for (Document document : splitDocuments) {
            EsDocumentChunk chunk = new EsDocumentChunk();
            chunk.setId(document.getId());
            chunk.setContent(document.getText());
            chunk.setMetadata(document.getMetadata());
            chunks.add(chunk);
        }
        return chunks;
    }

    private String normalizeSource(String source) {
        return new File(source).getAbsoluteFile().getAbsolutePath();
    }

    private String buildSourceFilterExpression(String source) {
        return "source == '" + source.replace("\\", "\\\\").replace("'", "\\'") + "'";
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
        chunk.setMetadata(metadata);
        return chunk;
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
