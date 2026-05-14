package com.shenchen.cloudcoldagent.service.usermemory.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryStore;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
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
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final UserLongTermMemoryMetadataService metadataService;
    private final VectorStore vectorStore;

    public UserLongTermMemoryStoreImpl(ElasticsearchClient elasticsearchClient,
                                       LongTermMemoryProperties properties,
                                       ObjectMapper objectMapper,
                                       UserLongTermMemoryMetadataService metadataService,
                                       @Qualifier("longTermMemoryVectorStore") VectorStore vectorStore) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metadataService = metadataService;
        this.vectorStore = vectorStore;
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
        if (!vectorDocuments.isEmpty()) {
            bulkBuilder.refresh(Refresh.True);
            elasticsearchClient.bulk(bulkBuilder.build());
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
        Filter.Expression userIdFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("userId"),
                new Filter.Value(userId));
        org.springframework.ai.vectorstore.SearchRequest request = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.getSimilarityThreshold())
                .filterExpression(userIdFilter)
                .build();
        List<Document> recalled = vectorStore.similaritySearch(request);
        List<Document> documents = recalled == null ? List.of() : new ArrayList<>(recalled);
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
        List<String> ids = getAllMemoryIdsByQuery(q -> q.term(t -> t.field("userId").value(userId)));
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
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (String memoryId : validIds) {
            bulkBuilder.operations(op -> op.delete(d -> d.index(properties.getKeywordIndexName()).id(memoryId)));
        }
        bulkBuilder.refresh(Refresh.True);
        elasticsearchClient.bulk(bulkBuilder.build());
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
        List<String> ids = getAllMemoryIdsByQuery(q -> q.bool(bool -> bool
                .must(m -> m.term(t -> t.field("userId").value(userId)))
                .must(m -> m.term(t -> t.field("originConversationId").value(conversationId)))));
        elasticsearchClient.deleteByQuery(DeleteByQueryRequest.of(b -> b
                .index(properties.getKeywordIndexName())
                .refresh(true)
                .query(q -> q.bool(bool -> bool
                        .must(m -> m.term(t -> t.field("userId").value(userId)))
                        .must(m -> m.term(t -> t.field("originConversationId").value(conversationId)))))));
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
    }

    /**
     * 使用 search_after 分页拉取关键词索引中符合查询条件的全部 memoryId。
     *
     * @param queryFn 查询条件构造器。
     * @return 命中的全部 memoryId 列表。
     * @throws Exception 查询索引失败时抛出。
     */
    private List<String> getAllMemoryIdsByQuery(java.util.function.Function<co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder,
            co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>> queryFn) throws Exception {
        List<String> allIds = new ArrayList<>();
        List<co.elastic.clients.elasticsearch._types.FieldValue> lastSortValues = null;
        while (true) {
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(properties.getKeywordIndexName())
                    .size(500)
                    .query(queryFn)
                    .sort(s -> s.field(f -> f.field("_id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
            if (lastSortValues != null) {
                builder.searchAfter(lastSortValues);
            }
            SearchResponse<UserLongTermMemoryDoc> response = elasticsearchClient.search(builder.build(),
                    UserLongTermMemoryDoc.class);
            List<Hit<UserLongTermMemoryDoc>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            for (Hit<UserLongTermMemoryDoc> hit : hits) {
                if (hit.id() != null) {
                    allIds.add(hit.id());
                }
                lastSortValues = hit.sort();
            }
            if (hits.size() < 500) {
                break;
            }
        }
        return allIds;
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
}
