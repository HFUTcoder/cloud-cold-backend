package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class KnowledgeMetadataSearchRequest implements Serializable {

    private Map<String, Object> metadataFilters;

    private Integer size;

    private static final long serialVersionUID = 1L;
}
