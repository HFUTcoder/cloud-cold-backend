package com.shenchen.cloudcoldagent.service.storage;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * `ElasticSearchService` 接口定义。
 */
public interface ElasticSearchService {

    /**
     * 创建 `create Index` 对应内容。
     *
     * @throws Exception 异常信息。
     */
    void createIndex() throws Exception;

    /**
     * 处理 `index Single` 对应逻辑。
     *
     * @param doc doc 参数。
     * @throws Exception 异常信息。
     */
    void indexSingle(EsDocumentChunk doc) throws Exception;

    /**
     * 处理 `bulk Index` 对应逻辑。
     *
     * @param docs docs 参数。
     * @throws Exception 异常信息。
     */
    void bulkIndex(List<EsDocumentChunk> docs) throws Exception;

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
     * 处理 `index Exists` 对应逻辑。
     *
     * @param indexName indexName 参数。
     * @return 返回处理结果。
     * @throws IOException 异常信息。
     */
    boolean indexExists(String indexName) throws IOException;

    /**
     * 处理 `search By Keyword` 对应逻辑。
     *
     * @param keyword keyword 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> searchByKeyword(String keyword) throws Exception;

    /**
     * 处理 `search By Keyword` 对应逻辑。
     *
     * @param keyword keyword 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer) throws Exception;

    /**
     * 处理 `search By Keyword` 对应逻辑。
     *
     * @param keyword keyword 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer,
                                          Map<String, Object> metadataFilters) throws Exception;

    /**
     * 处理 `search By Metadata` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters) throws Exception;

    /**
     * 处理 `search By Metadata` 对应逻辑。
     *
     * @param metadataFilters metadataFilters 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters, int size) throws Exception;

    /**
     * 处理 `vector Add Documents` 对应逻辑。
     *
     * @param documents documents 参数。
     * @throws Exception 异常信息。
     */
    void vectorAddDocuments(List<Document> documents) throws Exception;

    /**
     * 处理 `vector Add Chunks` 对应逻辑。
     *
     * @param chunks chunks 参数。
     * @throws Exception 异常信息。
     */
    void vectorAddChunks(List<EsDocumentChunk> chunks) throws Exception;

    /**
     * 处理 `similarity Search` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<Document> similaritySearch(String query) throws Exception;

    /**
     * 处理 `similarity Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param topK topK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @param filterExpression filterExpression 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<Document> similaritySearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    /**
     * 处理 `similarity Search` 对应逻辑。
     *
     * @param query query 参数。
     * @param topK topK 参数。
     * @param similarityThreshold similarityThreshold 参数。
     * @param metadataFilters metadataFilters 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    List<Document> similaritySearch(String query, int topK, double similarityThreshold,
                                    Map<String, Object> metadataFilters) throws Exception;

    /**
     * 处理 `vector Delete By Ids` 对应逻辑。
     *
     * @param ids ids 参数。
     * @throws Exception 异常信息。
     */
    void vectorDeleteByIds(List<String> ids) throws Exception;

    /**
     * 处理 `vector Delete By Filter` 对应逻辑。
     *
     * @param filterExpression filterExpression 参数。
     * @throws Exception 异常信息。
     */
    void vectorDeleteByFilter(String filterExpression) throws Exception;

    /**
     * 处理 `vector Index Exists` 对应逻辑。
     *
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    boolean vectorIndexExists() throws Exception;

    /**
     * 按 _id 批量获取文档。
     *
     * @param ids 文档 _id 列表。
     * @return 获取到的文档列表；缺失的 id 会被跳过。
     * @throws Exception ES 异常。
     */
    List<EsDocumentChunk> mget(List<String> ids) throws Exception;
}
