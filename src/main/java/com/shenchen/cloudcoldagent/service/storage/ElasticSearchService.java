package com.shenchen.cloudcoldagent.service.storage;

import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * `ElasticSearchService` 接口定义。
 */
public interface ElasticSearchService {

    void indexSingle(EsDocumentChunk doc) throws Exception;

    void bulkIndex(List<EsDocumentChunk> docs) throws Exception;

    void deleteByIds(List<String> ids) throws Exception;

    void deleteByDocumentId(Long documentId) throws Exception;

    void deleteBySource(String source) throws Exception;

    List<EsDocumentChunk> searchByKeyword(String keyword) throws Exception;

    List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer) throws Exception;

    /**
     * 按关键词执行全文检索，并可附带元数据过滤条件。
     */
    List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer,
                                          Map<String, Object> metadataFilters) throws Exception;

    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters) throws Exception;

    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters, int size) throws Exception;

    void vectorAddDocuments(List<Document> documents) throws Exception;

    void vectorAddChunks(List<EsDocumentChunk> chunks) throws Exception;

    List<Document> similaritySearch(String query) throws Exception;

    List<Document> similaritySearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    /**
     * 按元数据条件执行向量相似度检索。
     */
    List<Document> similaritySearch(String query, int topK, double similarityThreshold,
                                    Map<String, Object> metadataFilters) throws Exception;

    void vectorDeleteByIds(List<String> ids) throws Exception;

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
