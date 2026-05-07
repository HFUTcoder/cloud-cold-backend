package com.shenchen.cloudcoldagent.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * `KnowledgeWriteRequest` 类型实现。
 */
@Data
public class KnowledgeWriteRequest implements Serializable {

    private String filePath;

    private static final long serialVersionUID = 1L;
}
