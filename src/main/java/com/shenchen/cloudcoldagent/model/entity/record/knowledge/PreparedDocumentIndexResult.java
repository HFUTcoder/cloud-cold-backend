package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;

import java.util.List;

public record PreparedDocumentIndexResult(
        List<EsDocumentChunk> textChunks,
        List<KnowledgeDocumentImage> uploadedImages
) {
}
