package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.knowledge.KnowledgeDocumentImage;

import java.util.List;

public record PreparedDocumentIndexResult(
        List<EsDocumentChunk> parentChunks,
        List<EsDocumentChunk> childChunks,
        List<KnowledgeDocumentImage> uploadedImages
) {
}
