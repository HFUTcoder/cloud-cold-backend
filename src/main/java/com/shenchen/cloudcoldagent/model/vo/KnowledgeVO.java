package com.shenchen.cloudcoldagent.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * `KnowledgeVO` 类型实现。
 */
@Data
public class KnowledgeVO implements Serializable {

    private Long id;

    private Long userId;

    private String knowledgeName;

    private String description;

    private Integer documentCount;

    private LocalDateTime lastDocumentUploadTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
