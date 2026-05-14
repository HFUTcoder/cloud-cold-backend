package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

/**
 * `ExtractedDocumentImage` 记录对象。
 */
public record ExtractedDocumentImage(
        Integer imageIndex,
        Integer pageNumber,
        byte[] bytes,
        String contentType,
        String description
) {
}
