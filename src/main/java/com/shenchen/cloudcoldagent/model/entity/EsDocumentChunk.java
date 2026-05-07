package com.shenchen.cloudcoldagent.model.entity;

import lombok.Data;

import java.util.Map;

/**
 * `EsDocumentChunk` 类型实现。
 */
@Data
public class EsDocumentChunk {

    private String id;
    private String content;
    private Map<String, Object> metadata;
}
