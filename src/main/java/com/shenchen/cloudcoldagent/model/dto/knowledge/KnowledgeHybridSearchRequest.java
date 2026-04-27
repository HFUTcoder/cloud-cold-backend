package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

@Data
public class KnowledgeHybridSearchRequest implements Serializable {

    private String query;

    private Integer keywordSize;

    private Boolean useSmartAnalyzer;

    private Integer vectorTopK;

    private Double similarityThreshold;

    private String filterExpression;

    private static final long serialVersionUID = 1L;
}
