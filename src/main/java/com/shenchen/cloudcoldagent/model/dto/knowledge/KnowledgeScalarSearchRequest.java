package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * `KnowledgeScalarSearchRequest` 类型实现。
 */
@Data
public class KnowledgeScalarSearchRequest implements Serializable {

    private Long knowledgeId;

    private String query;

    private Integer size;

    private Boolean useSmartAnalyzer;

    private static final long serialVersionUID = 1L;
}
