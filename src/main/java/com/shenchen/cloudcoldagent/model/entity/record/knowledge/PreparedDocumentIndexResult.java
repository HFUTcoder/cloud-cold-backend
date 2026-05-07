package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;

import java.util.List;

/**
 * 创建 `PreparedDocumentIndexResult` 实例。
 *
 * @param textChunks textChunks 参数。
 * @param uploadedImages uploadedImages 参数。
 */
/**
 * `PreparedDocumentIndexResult` 记录对象。
 */
public record PreparedDocumentIndexResult(
        List<EsDocumentChunk> textChunks,
        List<KnowledgeDocumentImage> uploadedImages
) {
}
