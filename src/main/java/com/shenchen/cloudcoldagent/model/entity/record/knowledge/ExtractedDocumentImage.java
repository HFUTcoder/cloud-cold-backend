package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

public record ExtractedDocumentImage(
        Integer imageIndex,
        Integer pageNumber,
        byte[] bytes,
        String contentType,
        String description
) {
}
