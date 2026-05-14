package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

/**
 * `DocumentIndexContext` 记录对象。
 */
public record DocumentIndexContext(
        Long userId,
        Long knowledgeId,
        Long documentId,
        String documentName,
        String objectName,
        String documentSource,
        String fileType,
        String contentType,
        Long fileSize
) {
}
