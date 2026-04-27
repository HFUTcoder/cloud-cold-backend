package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ElasticSearchService {

    void createIndex() throws Exception;

    void indexSingle(EsDocumentChunk doc) throws Exception;

    void bulkIndex(List<EsDocumentChunk> docs) throws Exception;

    void deleteByIds(List<String> ids) throws Exception;

    void deleteBySource(String source) throws Exception;

    boolean indexExists(String indexName) throws IOException;

    List<EsDocumentChunk> searchByKeyword(String keyword) throws Exception;

    List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer) throws Exception;

    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters) throws Exception;

    List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters, int size) throws Exception;

    void vectorAddDocuments(List<Document> documents) throws Exception;

    void vectorAddChunks(List<EsDocumentChunk> chunks) throws Exception;

    List<Document> similaritySearch(String query) throws Exception;

    List<Document> similaritySearch(String query, int topK, double similarityThreshold, String filterExpression) throws Exception;

    void vectorDeleteByIds(List<String> ids) throws Exception;

    void vectorDeleteByFilter(String filterExpression) throws Exception;

    boolean vectorIndexExists() throws Exception;
}
