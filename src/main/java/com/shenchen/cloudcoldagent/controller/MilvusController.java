package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/milvus")
public class MilvusController {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<MilvusVectorStoreProperties> milvusVectorStorePropertiesProvider;

    public MilvusController(ObjectProvider<VectorStore> vectorStoreProvider,
                            ObjectProvider<MilvusVectorStoreProperties> milvusVectorStorePropertiesProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.milvusVectorStorePropertiesProvider = milvusVectorStorePropertiesProvider;
    }

    @GetMapping("/config")
    public BaseResponse<Map<String, Object>> config() {
        VectorStore vectorStore = requireVectorStore();
        MilvusVectorStoreProperties milvusVectorStoreProperties = milvusVectorStorePropertiesProvider.getIfAvailable();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("vectorStoreClass", vectorStore.getClass().getName());
        if (milvusVectorStoreProperties != null) {
            config.put("databaseName", milvusVectorStoreProperties.getDatabaseName());
            config.put("collectionName", milvusVectorStoreProperties.getCollectionName());
            config.put("embeddingDimension", milvusVectorStoreProperties.getEmbeddingDimension());
            config.put("indexType", String.valueOf(milvusVectorStoreProperties.getIndexType()));
            config.put("metricType", String.valueOf(milvusVectorStoreProperties.getMetricType()));
            config.put("idFieldName", milvusVectorStoreProperties.getIdFieldName());
            config.put("contentFieldName", milvusVectorStoreProperties.getContentFieldName());
            config.put("metadataFieldName", milvusVectorStoreProperties.getMetadataFieldName());
            config.put("embeddingFieldName", milvusVectorStoreProperties.getEmbeddingFieldName());
            config.put("autoId", milvusVectorStoreProperties.isAutoId());
        } else {
            config.put("milvusPropertiesLoaded", false);
        }
        return ResultUtils.success(config);
    }

    @PostMapping("/add")
    public BaseResponse<Map<String, Object>> add(@RequestBody AddDocumentRequest request) {
        VectorStore vectorStore = requireVectorStore();
        ThrowUtils.throwIf(request == null || request.text() == null || request.text().isBlank(), ErrorCode.PARAMS_ERROR);
        Document.Builder builder = Document.builder().text(request.text());
        if (request.id() != null && !request.id().isBlank()) {
            builder.id(request.id().trim());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            builder.metadata(request.metadata());
        }
        Document document = builder.build();
        vectorStore.add(List.of(document));
        return ResultUtils.success(Map.of(
                "id", document.getId(),
                "text", document.getText(),
                "metadata", document.getMetadata()
        ));
    }

    @PostMapping("/search")
    public BaseResponse<List<Map<String, Object>>> search(@RequestBody SearchRequest request) {
        VectorStore vectorStore = requireVectorStore();
        ThrowUtils.throwIf(request == null || request.query() == null || request.query().isBlank(), ErrorCode.PARAMS_ERROR);
        MilvusSearchRequest.MilvusBuilder builder = MilvusSearchRequest.milvusBuilder()
                .query(request.query())
                .topK(request.topK() == null || request.topK() <= 0 ? 4 : request.topK());

        if (request.similarityThreshold() != null) {
            builder.similarityThreshold(request.similarityThreshold());
        } else {
            builder.similarityThresholdAll();
        }
        if (request.filterExpression() != null && !request.filterExpression().isBlank()) {
            builder.filterExpression(request.filterExpression());
        }
        if (request.nativeExpression() != null && !request.nativeExpression().isBlank()) {
            builder.nativeExpression(request.nativeExpression());
        }
        if (request.searchParamsJson() != null && !request.searchParamsJson().isBlank()) {
            builder.searchParamsJson(request.searchParamsJson());
        }

        List<Map<String, Object>> result = vectorStore.similaritySearch(builder.build()).stream()
                .map(document -> Map.<String, Object>of(
                        "id", document.getId(),
                        "text", document.getText() == null ? "" : document.getText(),
                        "score", document.getScore(),
                        "metadata", document.getMetadata() == null ? Map.of() : document.getMetadata()
                ))
                .toList();
        return ResultUtils.success(result);
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> delete(@RequestBody DeleteRequest request) {
        VectorStore vectorStore = requireVectorStore();
        ThrowUtils.throwIf(request == null || request.ids() == null || request.ids().isEmpty(), ErrorCode.PARAMS_ERROR);
        vectorStore.delete(request.ids());
        return ResultUtils.success(true);
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "当前未找到 VectorStore Bean，请先确认 Milvus 连接与 spring.ai.vectorstore.milvus 配置是否生效");
        }
        return vectorStore;
    }

    public record AddDocumentRequest(String id, String text, Map<String, Object> metadata) {
    }

    public record SearchRequest(String query,
                                Integer topK,
                                Double similarityThreshold,
                                String filterExpression,
                                String nativeExpression,
                                String searchParamsJson) {
    }

    public record DeleteRequest(List<String> ids) {
    }
}
