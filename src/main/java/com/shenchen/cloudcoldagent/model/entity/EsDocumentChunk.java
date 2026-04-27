package com.shenchen.cloudcoldagent.model.entity;

import lombok.Data;

import java.util.Map;

@Data
public class EsDocumentChunk {

    private String id;
    private String content;
    private Map<String, Object> metadata;
}
