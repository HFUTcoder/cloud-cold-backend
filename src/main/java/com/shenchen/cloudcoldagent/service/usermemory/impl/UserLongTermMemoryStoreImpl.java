package com.shenchen.cloudcoldagent.service.usermemory.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class UserLongTermMemoryStoreImpl implements UserLongTermMemoryStore {

    private final ElasticsearchClient elasticsearchClient;
    private final RestClient restClient;
    private final EmbeddingModel embeddingModel;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;

    private ElasticsearchVectorStore vectorStore;

    public UserLongTermMemoryStoreImpl(ElasticsearchClient elasticsearchClient,
                                       RestClient restClient,
                                       EmbeddingModel embeddingModel,
                                       LongTermMemoryProperties properties,
                                       ObjectMapper objectMapper) {
        this.elasticsearchClient = elasticsearchClient;
        this.restClient = restClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(properties.getVectorIndexName());
        options.setDimensions(properties.getVectorDimensions());
        this.vectorStore = ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
        ensureIndexes();
    }

    @Override
    public void ensureIndexes() {
        try {
            if (!indexExists(properties.getKeywordIndexName())) {
                createKeywordIndex();
            }
            if (!vectorStore.indexExists()) {
                vectorStore.afterPropertiesSet();
            }
        } catch (Exception e) {
            log.warn("初始化长期记忆索引失败，message={}", e.getMessage(), e);
        }
    }

    @Override
    public void addMemories(Long userId, List<UserLongTermMemoryDoc> memories) throws Exception {
        if (userId == null || userId <= 0 || memories == null || memories.isEmpty()) {
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        List<Document> vectorDocuments = new ArrayList<>();
        for (UserLongTermMemoryDoc memory : memories) {
            if (memory == null || memory.getId() == null || memory.getId().isBlank()) {
                continue;
            }
            if (memory.getUpdatedAt() == null) {
                memory.setUpdatedAt(LocalDateTime.now());
            }
            if (memory.getCreatedAt() == null) {
                memory.setCreatedAt(memory.getUpdatedAt());
            }
            bulkBuilder.operations(op -> op.index(idx -> idx
                    .index(properties.getKeywordIndexName())
                    .id(memory.getId())
                    .document(memory)));

            vectorDocuments.add(Document.builder()
                    .id(memory.getId())
                    .text(Objects.toString(memory.getEmbeddingText(), memory.getContent()))
                    .metadata(buildMetadata(memory))
                    .build());
        }
        bulkBuilder.refresh(Refresh.True);
        elasticsearchClient.bulk(bulkBuilder.build());
        if (!vectorDocuments.isEmpty()) {
            vectorStore.add(vectorDocuments);
        }
    }

    @Override
    public List<UserLongTermMemoryDoc> listByUserId(Long userId, int size) throws Exception {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        SearchRequest request = SearchRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .size(size)
                .query(q -> q.term(t -> t.field("userId").value(userId)))
                .sort(s -> s.field(f -> f.field("updatedAt").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))));
        SearchResponse<UserLongTermMemoryDoc> response = elasticsearchClient.search(request, UserLongTermMemoryDoc.class);
        List<UserLongTermMemoryDoc> result = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                result.add(hit.source());
            }
        });
        return result;
    }

    @Override
    public List<UserLongTermMemoryDoc> similaritySearch(Long userId, String query, int topK) throws Exception {
        if (userId == null || userId <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        org.springframework.ai.vectorstore.SearchRequest request = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThresholdAll()
                .build();
        List<Document> recalled = vectorStore.similaritySearch(request);
        List<Document> documents = recalled == null
                ? List.of()
                : recalled.stream()
                .filter(document -> matchesUserId(document, userId))
                .limit(topK)
                .toList();
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> ids = documents.stream()
                .map(Document::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        SearchRequest keywordRequest = SearchRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .size(ids.size())
                .query(q -> q.bool(bool -> bool
                        .must(m -> m.term(t -> t.field("userId").value(userId)))
                        .must(m -> m.terms(t -> t.field("id").terms(v -> v.value(ids.stream().map(FieldValue::of).toList())))))));
        SearchResponse<UserLongTermMemoryDoc> response = elasticsearchClient.search(keywordRequest, UserLongTermMemoryDoc.class);
        Map<String, UserLongTermMemoryDoc> byId = new LinkedHashMap<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null && hit.id() != null) {
                byId.put(hit.id(), hit.source());
            }
        });
        List<UserLongTermMemoryDoc> result = new ArrayList<>();
        for (String id : ids) {
            UserLongTermMemoryDoc memory = byId.get(id);
            if (memory != null) {
                result.add(memory);
            }
        }
        return result;
    }

    @Override
    public void deleteById(Long userId, String memoryId) throws Exception {
        if (userId == null || userId <= 0 || memoryId == null || memoryId.isBlank()) {
            return;
        }
        elasticsearchClient.delete(d -> d.index(properties.getKeywordIndexName()).id(memoryId).refresh(Refresh.True));
        vectorStore.delete(List.of(memoryId));
    }

    @Override
    public void deleteByUserId(Long userId) throws Exception {
        if (userId == null || userId <= 0) {
            return;
        }
        List<String> ids = listByUserId(userId, 1000).stream()
                .map(UserLongTermMemoryDoc::getId)
                .filter(Objects::nonNull)
                .toList();
        elasticsearchClient.deleteByQuery(DeleteByQueryRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .refresh(true)
                .query(q -> q.term(t -> t.field("userId").value(userId)))));
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
    }

    @Override
    public void deleteByConversationId(Long userId, String conversationId) throws Exception {
        if (userId == null || userId <= 0 || conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<String> ids = listByUserId(userId, 1000).stream()
                .filter(memory -> conversationId.equals(memory.getOriginConversationId()))
                .map(UserLongTermMemoryDoc::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        elasticsearchClient.deleteByQuery(DeleteByQueryRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .refresh(true)
                .query(q -> q.bool(bool -> bool
                        .must(m -> m.term(t -> t.field("userId").value(userId)))
                        .must(m -> m.term(t -> t.field("originConversationId").value(conversationId)))))));
        vectorStore.delete(ids);
    }

    private boolean indexExists(String indexName) {
        try {
            return elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            return false;
        }
    }

    private void createKeywordIndex() throws Exception {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "id": { "type": "keyword" },
                      "userId": { "type": "long" },
                      "memoryType": { "type": "keyword" },
                      "title": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart"
                      },
                      "content": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart"
                      },
                      "summary": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart"
                      },
                      "embeddingText": { "type": "text", "index": false },
                      "confidence": { "type": "double" },
                      "importance": { "type": "double" },
                      "originConversationId": { "type": "keyword" },
                      "sourceConversationIds": { "type": "keyword" },
                      "sourceHistoryIds": { "type": "long" },
                      "createdAt": { "type": "date" },
                      "updatedAt": { "type": "date" }
                    }
                  }
                }
                """;
        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .withJson(new StringReader(mapping)));
        elasticsearchClient.indices().create(request);
    }

    private Map<String, Object> buildMetadata(UserLongTermMemoryDoc memory) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", memory.getUserId());
        metadata.put("memoryType", memory.getMemoryType());
        metadata.put("importance", memory.getImportance());
        metadata.put("confidence", memory.getConfidence());
        metadata.put("originConversationId", memory.getOriginConversationId());
        return metadata;
    }

    private boolean matchesUserId(Document document, Long userId) {
        if (document == null || document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return false;
        }
        Object actualUserId = document.getMetadata().get("userId");
        if (actualUserId == null) {
            return false;
        }
        if (actualUserId instanceof Number number) {
            return number.longValue() == userId;
        }
        return String.valueOf(userId).equals(String.valueOf(actualUserId));
    }
}
