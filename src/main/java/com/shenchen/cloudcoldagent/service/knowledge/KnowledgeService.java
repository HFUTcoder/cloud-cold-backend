package com.shenchen.cloudcoldagent.service.knowledge;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentIndexContext;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.PreparedDocumentIndexResult;
import com.shenchen.cloudcoldagent.model.vo.KnowledgeVO;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * `KnowledgeService` 接口定义。
 */
public interface KnowledgeService extends IService<com.shenchen.cloudcoldagent.model.entity.Knowledge> {

    /**
     * 创建 `create Knowledge` 对应内容。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    long createKnowledge(Long userId, KnowledgeAddRequest request);

    /**
     * 更新 `update Knowledge` 对应内容。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    boolean updateKnowledge(Long userId, KnowledgeUpdateRequest request);

    /**
     * 删除 `delete Knowledge` 对应内容。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    boolean deleteKnowledge(Long userId, Long knowledgeId);

    /**
     * 获取 `get Knowledge By Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    com.shenchen.cloudcoldagent.model.entity.Knowledge getKnowledgeById(Long userId, Long knowledgeId);

    /**
     * 处理 `page By User Id` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    Page<com.shenchen.cloudcoldagent.model.entity.Knowledge> pageByUserId(Long userId, KnowledgeQueryRequest request);

    /**
     * 获取 `get Query Wrapper` 对应结果。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    QueryWrapper getQueryWrapper(Long userId, KnowledgeQueryRequest request);

    /**
     * 获取 `get Knowledge VO` 对应结果。
     *
     * @param knowledge knowledge 参数。
     * @return 返回处理结果。
     */
    KnowledgeVO getKnowledgeVO(com.shenchen.cloudcoldagent.model.entity.Knowledge knowledge);

    /**
     * 获取 `get Knowledge VO List` 对应结果。
     *
     * @param knowledges knowledges 参数。
     * @return 返回处理结果。
     */
    List<KnowledgeVO> getKnowledgeVOList(List<com.shenchen.cloudcoldagent.model.entity.Knowledge> knowledges);

    /**
     * 处理 `refresh Knowledge Stats` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     */
    void refreshKnowledgeStats(Long userId, Long knowledgeId);

    /**
     * 处理 `prepare Document Index` 对应逻辑。
     *
     * @param file file 参数。
     * @param context context 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    PreparedDocumentIndexResult prepareDocumentIndex(File file, DocumentIndexContext context) throws Exception;

    /**
     * 处理 `store Prepared Chunks` 对应逻辑。
     *
     * @param chunks chunks 参数。
     * @throws Exception 异常信息。
     */
    void storePreparedChunks(List<EsDocumentChunk> chunks) throws Exception;

    /**
     * 处理 `index Document` 对应逻辑。
     *
     * @param file file 参数。
     * @param context context 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> indexDocument(File file, DocumentIndexContext context) throws Exception;

    /**
     * 处理 `add` 对应逻辑。
     *
     * @param filePath filePath 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> add(String filePath, Long userId, Long knowledgeId) throws Exception;

    /**
     * 更新 `update` 对应内容。
     *
     * @param filePath filePath 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> update(String filePath, Long userId, Long knowledgeId) throws Exception;

    /**
     * 删除 `delete By Ids` 对应内容。
     *
     * @param ids ids 参数。
     * @throws Exception 异常信息。
     */
    void deleteByIds(List<String> ids) throws Exception;

    /**
     * 删除 `delete By Document Id` 对应内容。
     *
     * @param documentId documentId 参数。
     * @throws Exception 异常信息。
     */
    void deleteByDocumentId(Long documentId) throws Exception;

    /**
     * 删除 `delete By Source` 对应内容。
     *
     * @param source source 参数。
     * @throws Exception 异常信息。
     */
    void deleteBySource(String source) throws Exception;

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> scalarSearch(String query) throws Exception;

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> scalarSearch(String query, int size, boolean useSmartAnalyzer) throws Exception;

    /**
     * 处理 `scalar Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query) throws Exception;

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
    List<EsDocumentChunk> scalarSearch(Long userId, Long knowledgeId, String query, int size,
                                       boolean useSmartAnalyzer) throws Exception;

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters) throws Exception;

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> metadataSearch(Map<String, Object> metadataFilters, int size) throws Exception;

    /**
     * 处理 `metadata Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters)
            throws Exception;

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
    List<EsDocumentChunk> metadataSearch(Long userId, Long knowledgeId, Map<String, Object> metadataFilters, int size)
            throws Exception;

    /**
     * 处理 `vector Search` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> vectorSearch(String query) throws Exception;

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
    List<EsDocumentChunk> vectorSearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    /**
     * 处理 `vector Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query) throws Exception;

    /**
     * 处理 `vector Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @param topK topK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> vectorSearch(Long userId, Long knowledgeId, String query, int topK,
                                       double similarityThreshold) throws Exception;

    /**
     * 处理 `hybrid Search` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> hybridSearch(String query) throws Exception;

    /**
     * 处理 `hybrid Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param keywordSize keywordSize 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @param vectorTopK vectorTopK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @param filterExpression filterExpression 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> hybridSearch(String query, int keywordSize, boolean useSmartAnalyzer, int vectorTopK,
                                       double similarityThreshold, String filterExpression) throws Exception;

    /**
     * 处理 `hybrid Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> hybridSearch(Long userId, Long knowledgeId, String query) throws Exception;

    /**
     * 处理 `hybrid Search` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @param keywordSize keywordSize 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @param vectorTopK vectorTopK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
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
