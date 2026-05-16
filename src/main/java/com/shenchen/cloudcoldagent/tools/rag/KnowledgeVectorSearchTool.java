package com.shenchen.cloudcoldagent.tools.rag;

import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `KnowledgeVectorSearchTool` 类型实现。
 */
@Component
public class KnowledgeVectorSearchTool extends AbstractKnowledgeSearchTool {

    public static final String TOOL_NAME = "knowledge_vector_search";

    public KnowledgeVectorSearchTool(KnowledgeService knowledgeService,
                                     ChatConversationService chatConversationService) {
        super(knowledgeService, chatConversationService);
    }

    @Tool(name = TOOL_NAME, description = "在指定知识库中执行向量检索，适合语义相近、表达改写或模糊问法；未传 knowledgeId 时默认使用当前会话已绑定知识库")
    public String knowledgeVectorSearch(@ToolParam(description = "知识库 ID，可为空；为空时默认使用当前会话已绑定知识库") Long knowledgeId,
                                        @ToolParam(description = "查询语句") String query,
                                        @ToolParam(description = "返回条数，可为空，默认 5") Integer topK,
                                        @ToolParam(description = "相似度阈值，可为空，默认 0.0") Double similarityThreshold) {
        logToolStart(TOOL_NAME, "query", query);
        try {
            validateQuery(query);
            Long userId = requireCurrentUserId();
            Long effectiveKnowledgeId = resolveKnowledgeId(knowledgeId);
            String formatted = formatSearchResults(
                    "vector",
                    effectiveKnowledgeId,
                    query,
                    knowledgeService.vectorSearch(
                            userId,
                            effectiveKnowledgeId,
                            query.trim(),
                            topK == null ? 5 : topK,
                            similarityThreshold == null ? 0.0d : similarityThreshold)
            );
            logToolSuccess(TOOL_NAME, query.trim(), formatted);
            return formatted;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, query, e, "知识库向量检索执行失败：");
        }
    }
}
