package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

@Data
public class KnowledgeVectorSearchRequest implements Serializable {

    private Long knowledgeId;

    private String query;

    private Integer topK;

    private Double similarityThreshold;

    private static final long serialVersionUID = 1L;
}
