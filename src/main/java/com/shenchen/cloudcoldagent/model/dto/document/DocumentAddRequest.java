package com.shenchen.cloudcoldagent.model.dto.document;

import lombok.Data;

import java.io.Serializable;

@Data
public class DocumentAddRequest implements Serializable {

    private Long knowledgeId;

    private String documentName;

    private String documentUrl;

    private String objectName;

    private String documentSource;

    private String fileType;

    private String contentType;

    private Long fileSize;

    private String indexStatus;

    private static final long serialVersionUID = 1L;
}
