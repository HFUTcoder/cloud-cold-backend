package com.shenchen.cloudcoldagent.service.storage.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.storage.ElasticSearchService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Elasticsearch 服务实现，负责关键词索引、向量索引以及相关检索 / 删除辅助逻辑。
 */
@Service
@Slf4j
public class ElasticSearchServiceImpl implements ElasticSearchService {

    private static final int VECTOR_BATCH_SIZE = 9;

    private final ElasticsearchClient client;

    private final VectorStore vectorStore;

    private final ObjectMapper mapper = new ObjectMapper();

    private final FilterExpressionTextParser filterExpressionTextParser = new FilterExpressionTextParser();

    private static final String INDEX_NAME = "rag_docs";

    private static final String FIELD_CONTENT = "content";

    /**
     * 注入关键词索引和向量检索所需的客户端依赖。
     *
     * @param client Elasticsearch Java 客户端。
     * @param vectorStore 向量存储实现。
     */
    public ElasticSearchServiceImpl(ElasticsearchClient client, VectorStore vectorStore) {
        this.client = client;
        this.vectorStore = vectorStore;
    }

    /**
     * 在服务启动时确保关键词索引存在。
     */
    @PostConstruct
    public void init() {
        try {
            if (!indexExists(INDEX_NAME)) {
                createIndex();
                log.info("ES index [{}] created with IK analyzer!", INDEX_NAME);
            } else {
                log.info("ES index [{}] already exists, skip creation.", INDEX_NAME);
            }
        } catch (Exception e) {
            log.error("Failed to create ES index: {}", e.getMessage(), e);
        }
    }

    /**
     * 创建索引（IK 分词 + 停用词 + lowercase）
     */
    @Override
    public void createIndex() throws Exception {
        // 1. 设置索引配置（settings）和 mapping
        String settingsAndMappingJson = """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                    "analysis": {
                      "filter": {
                        "my_stop_filter": {
                          "type": "stop",
                          "stopwords": "_chinese_"
                        },
                        "edge_ngram_filter": {
                          "type": "edge_ngram",
                          "min_gram": 1,
                          "max_gram": 20
                        }
                      },
                      "analyzer": {
                        "ik_max": {
                          "type": "custom",
                          "tokenizer": "ik_max_word",
                          "filter": ["lowercase", "my_stop_filter"]
                        },
                        "ik_smart": {
                          "type": "custom",
                          "tokenizer": "ik_smart",
                          "filter": ["lowercase", "my_stop_filter"]
                        },
                        "ngram_index_analyzer": {
                          "type": "custom",
                          "tokenizer": "standard",
                          "filter": ["lowercase", "edge_ngram_filter"]
                        },
                        "ngram_search_analyzer": {
                          "type": "custom",
                          "tokenizer": "standard",
                          "filter": ["lowercase"]
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "id": { "type": "keyword" },
                      "content": {
                        "type": "text",
                        "analyzer": "ik_max",
                        "search_analyzer": "ik_smart",
                        "fields": {
                          "smart": {
                            "type": "text",
                            "analyzer": "ik_smart",
                            "search_analyzer": "ik_smart"
                          },
                          "ngram": {
                            "type": "text",
                            "analyzer": "ngram_index_analyzer",
                            "search_analyzer": "ngram_search_analyzer"
                          }
                        }
                      },
                          "metadata": {
                        "type": "object",
                        "properties": {
                          "userId": { "type": "long" },
                          "knowledgeId": { "type": "long" },
                          "documentId": { "type": "long" },
                          "parentId": { "type": "long" },
                          "parentType": { "type": "keyword" },
                          "parentChunkId": { "type": "keyword" },
                          "chunkType": { "type": "keyword" },
                          "pageNumber": { "type": "integer" },
                          "objectName": { "type": "keyword" },
                          "source": { "type": "keyword" },
                          "fileName": { "type": "keyword" },
                          "documentName": { "type": "keyword" },
                          "fileType": { "type": "keyword" },
                          "contentType": { "type": "keyword" },
                          "imageIds": { "type": "long" },
                          "chunk_index": { "type": "integer" },
                          "chunk_total": { "type": "integer" }
                        }
                      }
                    }
                  }
                }
                """;

        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(INDEX_NAME)
                .withJson(new StringReader(settingsAndMappingJson))
        );

        // 3. 创建索引
        client.indices().create(request);
    }

    /**
     * 单条存储
     */
    @Override
    public void indexSingle(EsDocumentChunk doc) throws Exception {
        if (doc == null || doc.getId() == null) {
            throw new IllegalArgumentException("Document or ID cannot be null");
        }

        String docJson = mapper.writeValueAsString(doc);

        IndexRequest<EsDocumentChunk> request = IndexRequest.of(b -> b
                .index(INDEX_NAME)
                .id(doc.getId())
                .withJson(new StringReader(docJson))
                .refresh(Refresh.True)
        );

        client.index(request);
        log.debug("Indexed doc id={}", doc.getId());
    }

    /**
     * 批量存储
     */
    @Override
    public void bulkIndex(List<EsDocumentChunk> docs) throws Exception {
        if (docs == null || docs.isEmpty()) return;

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (EsDocumentChunk doc : docs) {
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(INDEX_NAME)
                            .id(doc.getId())
                            .document(doc)
                    )
            );
        }

        bulkBuilder.refresh(Refresh.True);

        BulkResponse response = client.bulk(bulkBuilder.build());
        if (response.errors()) {
            log.error("Bulk indexing completed with failures");
            response.items().forEach(item -> {
                if (item.error() != null) {
                    log.error("Failed to index doc {}: {}", item.id(), item.error().reason());
                }
            });
        } else {
            log.info("Successfully indexed {} documents", docs.size());
        }
    }

    /**
     * 删除 `delete By Ids` 对应内容。
     *
     * @param ids ids 参数。
     * @throws Exception 异常信息。
     */
    @Override
    public void deleteByIds(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            bulkBuilder.operations(op -> op
                    .delete(delete -> delete
                            .index(INDEX_NAME)
                            .id(id)
                    )
            );
        }

        bulkBuilder.refresh(Refresh.True);
        BulkResponse response = client.bulk(bulkBuilder.build());
        if (response.errors()) {
            log.error("Bulk delete completed with failures");
            response.items().forEach(item -> {
                if (item.error() != null) {
                    log.error("Failed to delete doc {}: {}", item.id(), item.error().reason());
                }
            });
        } else {
            log.info("Successfully deleted {} keyword documents", ids.size());
        }
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

        client.deleteByQuery(DeleteByQueryRequest.of(b -> b
                .index(INDEX_NAME)
                .refresh(true)
                .query(q -> q
                        .term(t -> t
                                .field("metadata.documentId")
                                .value(documentId)
                        )
                )
        ));
    }

    /**
     * 删除 `delete By Source` 对应内容。
     *
     * @param source source 参数。
     * @throws Exception 异常信息。
     */
    @Override
    public void deleteBySource(String source) throws Exception {
        if (source == null || source.isBlank()) {
            return;
        }

        client.deleteByQuery(DeleteByQueryRequest.of(b -> b
                .index(INDEX_NAME)
                .refresh(true)
                .query(q -> q
                        .term(t -> t
                                .field("metadata.source")
                                .value(source)
                        )
                )
        ));
    }

    /**
     * 判断指定关键词索引是否存在。
     *
     * @param indexName 索引名。
     * @return 存在时返回 true。
     * @throws IOException 查询索引状态失败时抛出。
     */
    @Override
    public boolean indexExists(String indexName) throws IOException {
        ExistsRequest request = ExistsRequest.of(b -> b.index(indexName));
        return client.indices().exists(request).value();
    }

    /**
     * 中文检索 - ik_max_word 建库 + ik_smart 检索
     */
    @Override
    public List<EsDocumentChunk> searchByKeyword(String keyword) throws Exception {
        return searchByKeyword(keyword, 5, false);
    }

    /**
     * 中文检索：ik_max_word / ik_smart 切换
     */
    @Override
    public List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer) throws Exception {
        return searchByKeyword(keyword, size, useSmartAnalyzer, Collections.emptyMap());
    }

    /**
     * 按关键词执行全文检索，并可附带元数据过滤条件。
     *
     * @param keyword 查询关键词。
     * @param size 返回条数上限。
     * @param useSmartAnalyzer 是否使用智能分词字段。
     * @param metadataFilters 元数据过滤条件。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer,
                                                 Map<String, Object> metadataFilters) throws Exception {
        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> filters = buildMetadataFilterQueries(metadataFilters);

        SearchRequest request = SearchRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q
                        .bool(bool -> bool
                                .must(must -> must
                                        .match(m -> m
                                                .field(FIELD_CONTENT + ".ngram")
                                                .query(keyword)
                                        )
                                )
                                .filter(filters)
                        )
                )
                .size(size)
        );

        SearchResponse<EsDocumentChunk> response = client.search(request, EsDocumentChunk.class);

        List<EsDocumentChunk> result = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                EsDocumentChunk chunk = hit.source();
                Map<String, Object> metadata = chunk.getMetadata() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(chunk.getMetadata());
                if (hit.score() != null) {
                    metadata.put("keyword_score", hit.score());
                }
                chunk.setMetadata(metadata);
                result.add(chunk);
            }
        });

        return result;
    }

    /**
     * 按元数据条件执行检索。
     *
     * @param metadataFilters 元数据过滤条件。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters) throws Exception {
        return searchByMetadata(metadataFilters, 10);
    }

    /**
     * 按元数据条件执行检索，并限制返回数量。
     *
     * @param metadataFilters 元数据过滤条件。
     * @param size 返回条数上限。
     * @return 命中的 chunk 列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters, int size) throws Exception {
        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> filters = buildMetadataFilterQueries(metadataFilters);
        if (filters.isEmpty()) {
            return Collections.emptyList();
        }

        SearchRequest request = SearchRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q
                        .bool(bool -> bool.filter(filters))
                )
                .size(size)
        );

        SearchResponse<EsDocumentChunk> response = client.search(request, EsDocumentChunk.class);
        List<EsDocumentChunk> result = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                result.add(hit.source());
            }
        });
        return result;
    }

    /**
     * 将 Spring AI Document 批量写入向量索引。
     *
     * @param documents 待写入的文档列表。
     * @throws Exception 写入向量索引失败时抛出。
     */
    @Override
    public void vectorAddDocuments(List<Document> documents) throws Exception {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        List<Document> validDocuments = documents.stream()
                .filter(Objects::nonNull)
                .filter(doc -> doc.getText() != null && !doc.getText().isBlank())
                .toList();
        if (validDocuments.isEmpty()) {
            return;
        }

        for (int i = 0; i < validDocuments.size(); i += VECTOR_BATCH_SIZE) {
            List<Document> batch = validDocuments.subList(i, Math.min(i + VECTOR_BATCH_SIZE, validDocuments.size()));
            vectorStore.add(batch);
        }
        log.info("Successfully indexed {} vector documents", validDocuments.size());
    }

    /**
     * 将内部 chunk 列表转换后批量写入向量索引。
     *
     * @param chunks 待写入的 chunk 列表。
     * @throws Exception 写入向量索引失败时抛出。
     */
    @Override
    public void vectorAddChunks(List<EsDocumentChunk> chunks) throws Exception {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<Document> documents = chunks.stream()
                .filter(Objects::nonNull)
                .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isBlank())
                .map(this::toVectorDocument)
                .toList();

        vectorAddDocuments(documents);
    }

    /**
     * 执行默认参数的向量相似度检索。
     *
     * @param query 查询文本。
     * @return 命中的向量文档列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<Document> similaritySearch(String query) throws Exception {
        return similaritySearch(query, org.springframework.ai.vectorstore.SearchRequest.DEFAULT_TOP_K,
                org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL, (String) null);
    }

    /**
     * 按指定参数执行向量相似度检索，可附带过滤表达式。
     *
     * @param query 查询文本。
     * @param topK 返回条数上限。
     * @param similarityThreshold 相似度阈值。
     * @param filterExpression 过滤表达式。
     * @return 命中的向量文档列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<Document> similaritySearch(String query, int topK, double similarityThreshold, String filterExpression)
            throws Exception {
        org.springframework.ai.vectorstore.SearchRequest.Builder builder = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK);

        if (similarityThreshold == org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL) {
            builder.similarityThresholdAll();
        }
        else {
            builder.similarityThreshold(similarityThreshold);
        }

        if (filterExpression != null && !filterExpression.isBlank()) {
            builder.filterExpression(filterExpression);
        }

        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 按元数据条件执行向量相似度检索。
     *
     * @param query 查询文本。
     * @param topK 返回条数上限。
     * @param similarityThreshold 相似度阈值。
     * @param metadataFilters 元数据过滤条件。
     * @return 过滤后的向量文档列表。
     * @throws Exception 检索失败时抛出。
     */
    @Override
    public List<Document> similaritySearch(String query, int topK, double similarityThreshold,
                                           Map<String, Object> metadataFilters) throws Exception {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return similaritySearch(query, topK, similarityThreshold, (String) null);
        }

        String filterExpression = buildFilterExpression(metadataFilters);
        return similaritySearch(query, topK, similarityThreshold, filterExpression);
    }

    /**
     * 按 id 批量删除向量索引中的文档。
     *
     * @param ids 文档 id 列表。
     * @throws Exception 删除失败时抛出。
     */
    @Override
    public void vectorDeleteByIds(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    /**
     * 按过滤表达式删除向量索引中的文档。
     *
     * @param filterExpression 过滤表达式。
     * @throws Exception 删除失败时抛出。
     */
    @Override
    public void vectorDeleteByFilter(String filterExpression) throws Exception {
        if (filterExpression == null || filterExpression.isBlank()) {
            return;
        }
        Filter.Expression expression = filterExpressionTextParser.parse(filterExpression);
        vectorStore.delete(expression);
    }

    /**
     * 判断向量索引是否已就绪。
     *
     * @return 向量索引可用时返回 true。
     * @throws Exception 查询索引状态失败时抛出。
     */
    @Override
    public boolean vectorIndexExists() throws Exception {
        if (vectorStore instanceof ElasticsearchVectorStore elasticsearchVectorStore) {
            return elasticsearchVectorStore.indexExists();
        }
        return vectorStore.getNativeClient().isPresent();
    }

    /**
     * 将内部 chunk 结构转换成向量存储所需的 Document。
     *
     * @param chunk 原始 chunk。
     * @return 向量文档对象。
     */
    private Document toVectorDocument(EsDocumentChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(chunk.getMetadata());

        return Document.builder()
                .id(chunk.getId())
                .text(chunk.getContent())
                .metadata(Collections.unmodifiableMap(metadata))
                .build();
    }

    /**
     * 将元数据过滤条件转换成 Elasticsearch term 查询列表。
     *
     * @param metadataFilters 元数据过滤条件。
     * @return ES filter query 列表。
     */
    private List<co.elastic.clients.elasticsearch._types.query_dsl.Query> buildMetadataFilterQueries(
            Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return Collections.emptyList();
        }

        return metadataFilters.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                .map(entry -> co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                        .term(t -> t
                                .field("metadata." + entry.getKey())
                                .value(toFieldValue(entry.getValue()))
                        )))
                .toList();
    }

    /**
     * 将任意 Java 对象转换成 Elasticsearch 可接受的 FieldValue。
     *
     * @param value 原始值。
     * @return ES FieldValue。
     */
    private FieldValue toFieldValue(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return FieldValue.of(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return FieldValue.of(((Number) value).doubleValue());
        }
        if (value instanceof Boolean booleanValue) {
            return FieldValue.of(booleanValue);
        }
        return FieldValue.of(value.toString());
    }

    /**
     * 将元数据过滤条件转换成向量存储过滤表达式。
     *
     * @param metadataFilters 元数据过滤条件。
     * @return 过滤表达式文本。
     */
    private String buildFilterExpression(Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return null;
        }

        return metadataFilters.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                .map(entry -> entry.getKey() + " == " + formatFilterValue(entry.getValue()))
                .reduce((left, right) -> left + " && " + right)
                .orElse(null);
    }


    /**
     * 将过滤值格式化成向量存储过滤表达式中的文本片段。
     *
     * @param value 原始值。
     * @return 格式化后的表达式值。
     */
    private String formatFilterValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        String text = value.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        return "'" + text + "'";
    }

    @Override
    public List<EsDocumentChunk> mget(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> cleanIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (cleanIds.isEmpty()) {
            return List.of();
        }

        MgetRequest request = MgetRequest.of(b -> b
                .index(INDEX_NAME)
                .ids(cleanIds));
        MgetResponse<EsDocumentChunk> response = client.mget(request, EsDocumentChunk.class);
        List<EsDocumentChunk> results = new ArrayList<>();
        for (var item : response.docs()) {
            if (item.isResult()) {
                var getResult = item.result();
                if (getResult.source() != null) {
                    results.add(getResult.source());
                }
            }
        }
        return results;
    }
}
