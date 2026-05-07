package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * `KnowledgeUpdateRequest` 类型实现。
 */
@Data
public class KnowledgeUpdateRequest implements Serializable {

    private Long id;

    private String knowledgeName;

    private String description;

    private static final long serialVersionUID = 1L;
}
