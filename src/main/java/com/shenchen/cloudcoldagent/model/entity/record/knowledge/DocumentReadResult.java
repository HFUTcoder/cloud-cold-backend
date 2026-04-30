package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import org.springframework.ai.document.Document;

import java.util.List;

public record DocumentReadResult(
        List<Document> documents,
        List<ExtractedDocumentImage> extractedImages
) {
}
