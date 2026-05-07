package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * `KnowledgeAddRequest` 类型实现。
 */
@Data
public class KnowledgeAddRequest implements Serializable {

    private String knowledgeName;

    private String description;

    private static final long serialVersionUID = 1L;
}
