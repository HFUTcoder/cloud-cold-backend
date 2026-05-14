package com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge;

import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.vo.agent.RetrievedKnowledgeImage;

import java.util.List;

/**
 * `KnowledgePreprocessResult` 记录对象。
 */
public record KnowledgePreprocessResult(
        String effectiveQuestion,
        List<EsDocumentChunk> retrievedChunks,
        List<RetrievedKnowledgeImage> retrievedImages,
        boolean retrievalTriggered
) {
}
