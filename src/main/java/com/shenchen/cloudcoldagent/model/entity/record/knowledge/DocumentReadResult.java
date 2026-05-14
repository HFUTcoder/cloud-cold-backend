package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * `DocumentReadResult` 记录对象。
 */
public record DocumentReadResult(
        List<Document> documents,
        List<ExtractedDocumentImage> extractedImages
) {
}
