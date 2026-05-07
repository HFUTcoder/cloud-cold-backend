package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 创建 `DocumentReadResult` 实例。
 *
 * @param documents documents 参数。
 * @param extractedImages extractedImages 参数。
 */
/**
 * `DocumentReadResult` 记录对象。
 */
public record DocumentReadResult(
        List<Document> documents,
        List<ExtractedDocumentImage> extractedImages
) {
}
