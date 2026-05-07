package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * `KnowledgeMetadataSearchRequest` 类型实现。
 */
@Data
public class KnowledgeMetadataSearchRequest implements Serializable {

    private Long knowledgeId;

    private Map<String, Object> metadataFilters;

    private Integer size;

    private static final long serialVersionUID = 1L;
}
