package com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;

import java.util.List;

/**
 * 创建 `KnowledgePreprocessResult` 实例。
 *
 * @param effectiveQuestion effectiveQuestion 参数。
 * @param retrievedChunks retrievedChunks 参数。
 * @param retrievedImages retrievedImages 参数。
 * @param retrievalTriggered retrievalTriggered 参数。
 */
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
