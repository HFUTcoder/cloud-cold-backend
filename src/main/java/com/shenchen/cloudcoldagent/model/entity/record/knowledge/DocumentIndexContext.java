package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

/**
 * 创建 `DocumentIndexContext` 实例。
 *
 * @param userId userId 参数。
 * @param knowledgeId knowledgeId 参数。
 * @param documentId documentId 参数。
 * @param documentName documentName 参数。
 * @param objectName objectName 参数。
 * @param documentSource documentSource 参数。
 * @param fileType fileType 参数。
 * @param contentType contentType 参数。
 * @param fileSize fileSize 参数。
 */
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
