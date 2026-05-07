package com.shenchen.cloudcoldagent.service.usermemory.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryStore;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
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

/**
 * 长期记忆存储服务实现，负责关键词索引、向量索引以及相关召回逻辑。
 */
@Service
@Slf4j
public class UserLongTermMemoryStoreImpl implements UserLongTermMemoryStore {

    private final ElasticsearchClient elasticsearchClient;
    private final RestClient restClient;
    private final EmbeddingModel embeddingModel;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final UserLongTermMemoryMetadataService metadataService;

    private ElasticsearchVectorStore vectorStore;

    /**
     * 注入长期记忆存储所需的 ES、向量和元数据依赖。
     *
     * @param elasticsearchClient ES Java 客户端。
     * @param restClient ES RestClient。
     * @param embeddingModel 向量化模型。
     * @param properties 长期记忆配置。
     * @param objectMapper 对象映射器。
     * @param metadataService 长期记忆元数据服务。
     */
    public UserLongTermMemoryStoreImpl(ElasticsearchClient elasticsearchClient,
                                       RestClient restClient,
                                       EmbeddingModel embeddingModel,
                                       LongTermMemoryProperties properties,
                                       ObjectMapper objectMapper,
                                       UserLongTermMemoryMetadataService metadataService) {
        this.elasticsearchClient = elasticsearchClient;
        this.restClient = restClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metadataService = metadataService;
    }

    /**
     * 初始化长期记忆向量存储并确保索引存在。
     */
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

    /**
     * 确保关键词索引和向量索引都已创建完成。
     */
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

    /**
     * 批量写入长期记忆，同时同步更新关键词索引和向量索引。
     *
     * @param userId 当前用户 id。
     * @param memories 待写入的长期记忆文档。
     * @throws Exception 写入索引失败时抛出。
     */
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

    /**
     * 查询当前用户在关键词索引中的长期记忆列表。
     *
     * @param userId 当前用户 id。
     * @param size 返回条数上限。
     * @return 长期记忆文档列表。
     * @throws Exception 查询失败时抛出。
     */
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

    /**
     * 对当前用户的长期记忆执行向量相似度召回。
     *
     * @param userId 当前用户 id。
     * @param query 查询文本。
     * @param topK 召回数量上限。
     * @return 命中的长期记忆文档列表。
     * @throws Exception 查询失败时抛出。
     */
    @Override
    public List<UserLongTermMemoryDoc> similaritySearch(Long userId, String query, int topK) throws Exception {
        if (userId == null || userId <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        org.springframework.ai.vectorstore.SearchRequest request = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.getSimilarityThreshold())
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
        Map<String, UserLongTermMemoryDoc> byId = metadataService.mapActiveDocsByMemoryIds(userId, ids);
        List<UserLongTermMemoryDoc> result = new ArrayList<>();
        for (String id : ids) {
            UserLongTermMemoryDoc memory = byId.get(id);
            if (memory != null) {
                result.add(memory);
            }
        }
        return result;
    }

    /**
     * 删除 `delete By Id` 对应内容。
     *
     * @param userId userId 参数。
     * @param memoryId memoryId 参数。
     * @throws Exception 异常信息。
     */
    @Override
    public void deleteById(Long userId, String memoryId) throws Exception {
        if (userId == null || userId <= 0 || memoryId == null || memoryId.isBlank()) {
            return;
        }
        elasticsearchClient.delete(d -> d.index(properties.getKeywordIndexName()).id(memoryId).refresh(Refresh.True));
        vectorStore.delete(List.of(memoryId));
    }

    /**
     * 删除某个用户的全部长期记忆索引数据。
     *
     * @param userId 当前用户 id。
     * @throws Exception 删除关键词索引或向量索引失败时抛出。
     */
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

    /**
     * 批量删除指定 memoryId 的关键词索引和向量索引数据。
     *
     * @param userId 当前用户 id。
     * @param memoryIds 待删除的 memoryId 列表。
     * @throws Exception 删除索引失败时抛出。
     */
    @Override
    public void deleteByIds(Long userId, List<String> memoryIds) throws Exception {
        if (userId == null || userId <= 0 || memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        List<String> validIds = memoryIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            return;
        }
        for (String memoryId : validIds) {
            elasticsearchClient.delete(d -> d.index(properties.getKeywordIndexName()).id(memoryId).refresh(Refresh.True));
        }
        vectorStore.delete(validIds);
    }

    /**
     * 删除某个会话来源对应的长期记忆索引数据。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @throws Exception 删除关键词索引或向量索引失败时抛出。
     */
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

    /**
     * 判断指定关键词索引是否已经存在。
     *
     * @param indexName 索引名。
     * @return 索引存在时返回 true。
     */
    private boolean indexExists(String indexName) {
        try {
            return elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建长期记忆关键词索引及其字段映射。
     *
     * @throws Exception 创建索引失败时抛出。
     */
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

    /**
     * 为长期记忆构建写入向量存储时使用的元数据。
     *
     * @param memory 长期记忆文档。
     * @return 向量存储元数据映射。
     */
    private Map<String, Object> buildMetadata(UserLongTermMemoryDoc memory) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", memory.getUserId());
        metadata.put("memoryType", memory.getMemoryType());
        metadata.put("importance", memory.getImportance());
        metadata.put("confidence", memory.getConfidence());
        metadata.put("originConversationId", memory.getOriginConversationId());
        return metadata;
    }

    /**
     * 判断向量检索返回的文档是否属于当前用户。
     *
     * @param document 向量检索命中的文档。
     * @param userId 当前用户 id。
     * @return 命中当前用户时返回 true。
     */
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
