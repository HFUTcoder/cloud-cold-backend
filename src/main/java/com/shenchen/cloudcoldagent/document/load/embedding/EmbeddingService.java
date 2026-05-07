package com.shenchen.cloudcoldagent.document.load.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * `EmbeddingService` 类型实现。
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 创建 `EmbeddingService` 实例。
     *
     * @param embeddingModel embeddingModel 参数。
     */
    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 单文本向量化。
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /**
     * 文档列表向量化。
     */
    public List<float[]> embed(List<Document> documents) {
        return documents.stream()
                .map(Document::getText)
                .map(this::embed)
                .collect(Collectors.toList());
    }
}
