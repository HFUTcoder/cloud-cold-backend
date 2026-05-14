package com.shenchen.cloudcoldagent.service.knowledge;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.knowledge.Knowledge;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentIndexContext;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.PreparedDocumentIndexResult;
import com.shenchen.cloudcoldagent.model.vo.knowledge.KnowledgeVO;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * `KnowledgeService` 接口定义。
 */
public interface KnowledgeService extends IService<Knowledge> {

    long createKnowledge(Long userId, KnowledgeAddRequest request);

    boolean updateKnowledge(Long userId, KnowledgeUpdateRequest request);

    boolean deleteKnowledge(Long userId, Long knowledgeId);

    Knowledge getKnowledgeById(Long userId, Long knowledgeId);

    Page<Knowledge> pageByUserId(Long userId, KnowledgeQueryRequest request);

    QueryWrapper getQueryWrapper(Long userId, KnowledgeQueryRequest request);

    KnowledgeVO getKnowledgeVO(Knowledge knowledge);

    List<KnowledgeVO> getKnowledgeVOList(List<Knowledge> knowledges);

    void refreshKnowledgeStats(Long userId, Long knowledgeId);

    PreparedDocumentIndexResult prepareDocumentIndex(File file, DocumentIndexContext context) throws Exception;

    void storePreparedChunks(List<EsDocumentChunk> chunks) throws Exception;

    List<EsDocumentChunk> indexDocument(File file, DocumentIndexContext context) throws Exception;

    /**
     * 以本地文件路径为入口执行文档入库（调试用途）。
     */
    List<EsDocumentChunk> add(String filePath, Long userId, Long knowledgeId) throws Exception;

    /**
     * 先删除同 source 的旧索引，再重新执行入库。
     */
    List<EsDocumentChunk> update(String filePath, Long userId, Long knowledgeId) throws Exception;

    void deleteByIds(List<String> ids) throws Exception;

    void deleteByDocumentId(Long documentId) throws Exception;

    void deleteBySource(String source) throws Exception;

    List<EsDocumentChunk> scalarSearch(String query) throws Exception;

    List<EsDocumentChunk> scalarSearch(String query, int size, boolean useSmartAnalyzer) throws Exception;

    List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query) throws Exception;

    List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query, int size,
                                       boolean useSmartAnalyzer) throws Exception;

    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception;

    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception;

    List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters)
            throws Exception;

    List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters, int size)
            throws Exception;

    List<EsDocumentChunk> vectorSearch(String query) throws Exception;

    List<EsDocumentChunk> vectorSearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query) throws Exception;

    List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query, int topK,
                                       double similarityThreshold) throws Exception;

    List<EsDocumentChunk> hybridSearch(String query) throws Exception;

    List<EsDocumentChunk> hybridSearch(String query, int keywordSize, boolean useSmartAnalyzer, int vectorTopK,
                                       double similarityThreshold, String filterExpression) throws Exception;

    List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query) throws Exception;

    List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query, int keywordSize,
                                       boolean useSmartAnalyzer, int vectorTopK,
                                       double similarityThreshold) throws Exception;

    /**
     * 按 ES _id 批量获取文档 chunk。
     *
     * @param ids 文档 _id 列表。
     * @return 获取到的文档列表。
     * @throws Exception ES 异常。
     */
    List<EsDocumentChunk> mget(List<String> ids) throws Exception;
}
