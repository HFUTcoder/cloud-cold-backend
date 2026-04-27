package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.vo.KnowledgeVO;

import java.util.List;
import java.util.Map;

public interface KnowledgeService extends IService<com.shenchen.cloudcoldagent.model.entity.Knowledge> {

    long createKnowledge(Long userId, KnowledgeAddRequest request);

    boolean updateKnowledge(Long userId, KnowledgeUpdateRequest request);

    boolean deleteKnowledge(Long userId, Long knowledgeId);

    com.shenchen.cloudcoldagent.model.entity.Knowledge getKnowledgeById(Long userId, Long knowledgeId);

    Page<com.shenchen.cloudcoldagent.model.entity.Knowledge> pageByUserId(Long userId, KnowledgeQueryRequest request);

    QueryWrapper getQueryWrapper(Long userId, KnowledgeQueryRequest request);

    KnowledgeVO getKnowledgeVO(com.shenchen.cloudcoldagent.model.entity.Knowledge knowledge);

    List<KnowledgeVO> getKnowledgeVOList(List<com.shenchen.cloudcoldagent.model.entity.Knowledge> knowledges);

    void refreshKnowledgeStats(Long userId, Long knowledgeId);

    List<EsDocumentChunk> add(String filePath) throws Exception;

    List<EsDocumentChunk> update(String filePath) throws Exception;

    void deleteByIds(List<String> ids) throws Exception;

    void deleteBySource(String source) throws Exception;

    List<EsDocumentChunk> scalarSearch(String query) throws Exception;

    List<EsDocumentChunk> scalarSearch(String query, int size, boolean useSmartAnalyzer) throws Exception;

    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception;

    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception;

    List<EsDocumentChunk> vectorSearch(String query) throws Exception;

    List<EsDocumentChunk> vectorSearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    List<EsDocumentChunk> hybridSearch(String query) throws Exception;

    List<EsDocumentChunk> hybridSearch(String query, int keywordSize, boolean useSmartAnalyzer, int vectorTopK,
                                       double similarityThreshold, String filterExpression) throws Exception;
}
