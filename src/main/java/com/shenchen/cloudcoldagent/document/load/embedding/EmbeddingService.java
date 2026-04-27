package com.shenchen.cloudcoldagent.document.load.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmbeddingService {

    @Autowired
    private EmbeddingModel embeddingModel;

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
