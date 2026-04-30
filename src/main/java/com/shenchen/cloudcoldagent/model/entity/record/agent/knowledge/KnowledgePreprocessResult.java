package com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.List;

public record KnowledgePreprocessResult(
        String effectiveQuestion,
        List<EsDocumentChunk> retrievedChunks,
        List<RetrievedKnowledgeImage> retrievedImages,
        boolean retrievalTriggered
) {
}
