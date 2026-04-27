package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

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
