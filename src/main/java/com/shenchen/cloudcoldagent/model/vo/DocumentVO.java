package com.shenchen.cloudcoldagent.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * `DocumentVO` 类型实现。
 */
@Data
public class DocumentVO implements Serializable {

    private Long id;

    private Long userId;

    private Long knowledgeId;

    private String documentName;

    private String documentUrl;

    private String objectName;

    private String documentSource;

    private String fileType;

    private String contentType;

    private Long fileSize;

    private String indexStatus;

    private Integer chunkCount;

    private String indexErrorMessage;

    private LocalDateTime indexStartTime;

    private LocalDateTime indexEndTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
