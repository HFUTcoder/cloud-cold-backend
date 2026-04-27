package com.shenchen.cloudcoldagent.model.dto.document;

import lombok.Data;

import java.io.Serializable;

@Data
public class DocumentUpdateRequest implements Serializable {

    private Long id;

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

    private java.time.LocalDateTime indexStartTime;

    private java.time.LocalDateTime indexEndTime;

    private static final long serialVersionUID = 1L;
}
