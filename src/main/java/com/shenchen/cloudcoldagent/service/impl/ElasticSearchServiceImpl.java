package com.shenchen.cloudcoldagent.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class ElasticSearchServiceImpl implements ElasticSearchService {

    private static final int VECTOR_BATCH_SIZE = 9;

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private VectorStore vectorStore;

    private final ObjectMapper mapper = new ObjectMapper();
    private final FilterExpressionTextParser filterExpressionTextParser = new FilterExpressionTextParser();

    private static final String INDEX_NAME = "rag_docs";

    private static final String FIELD_CONTENT = "content";

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
                          }
                        }
                      },
                      "metadata": {
                        "type": "object",
                        "properties": {
                          "source": { "type": "keyword" },
                          "category": { "type": "keyword" },
                          "orderId": { "type": "keyword" }
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
        String field = useSmartAnalyzer ? FIELD_CONTENT + ".smart" : FIELD_CONTENT;

        SearchRequest request = SearchRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q
                        .match(m -> m
                                .field(field)
                                .query(keyword)
                        )
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

    @Override
    public List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters) throws Exception {
        return searchByMetadata(metadataFilters, 10);
    }

    @Override
    public List<EsDocumentChunk> searchByMetadata(Map<String, Object> metadataFilters, int size) throws Exception {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return Collections.emptyList();
        }

        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> filters = metadataFilters.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                .map(entry -> co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                        .term(t -> t
                                .field("metadata." + entry.getKey())
                                .value(entry.getValue().toString())
                        )))
                .toList();

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

    @Override
    public List<Document> similaritySearch(String query) throws Exception {
        return similaritySearch(query, org.springframework.ai.vectorstore.SearchRequest.DEFAULT_TOP_K,
                org.springframework.ai.vectorstore.SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL, null);
    }

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

    @Override
    public void vectorDeleteByIds(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    @Override
    public void vectorDeleteByFilter(String filterExpression) throws Exception {
        if (filterExpression == null || filterExpression.isBlank()) {
            return;
        }
        Filter.Expression expression = filterExpressionTextParser.parse(filterExpression);
        vectorStore.delete(expression);
    }

    @Override
    public boolean vectorIndexExists() throws Exception {
        if (vectorStore instanceof ElasticsearchVectorStore elasticsearchVectorStore) {
            return elasticsearchVectorStore.indexExists();
        }
        return vectorStore.getNativeClient().isPresent();
    }

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
}
