package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * `KnowledgeHybridSearchRequest` 类型实现。
 */
@Data
public class KnowledgeHybridSearchRequest implements Serializable {

    private Long knowledgeId;

    private String query;

    private Integer keywordSize;

    private Boolean useSmartAnalyzer;

    private Integer vectorTopK;

    private Double similarityThreshold;

    private static final long serialVersionUID = 1L;
}
