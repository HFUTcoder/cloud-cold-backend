package com.shenchen.cloudcoldagent.tools.rag;

import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `KnowledgeHybridSearchTool` 类型实现。
 */
@Component
public class KnowledgeHybridSearchTool extends AbstractKnowledgeSearchTool {

    private static final String TOOL_NAME = "knowledge_hybrid_search";

    public KnowledgeHybridSearchTool(KnowledgeService knowledgeService,
                                     ChatConversationService chatConversationService) {
        super(knowledgeService, chatConversationService);
    }

    @Tool(name = TOOL_NAME, description = "在指定知识库中执行混合检索，综合关键词与向量语义结果，适合作为知识库问答默认检索方式；未传 knowledgeId 时默认使用当前会话已绑定知识库")
    public String knowledgeHybridSearch(@ToolParam(description = "知识库 ID，可为空；为空时默认使用当前会话已绑定知识库") Long knowledgeId,
                                        @ToolParam(description = "查询语句") String query,
                                        @ToolParam(description = "关键词检索返回条数，可为空，默认 5") Integer keywordSize,
                                        @ToolParam(description = "是否启用智能分词，可为空，默认 false") Boolean useSmartAnalyzer,
                                        @ToolParam(description = "向量检索返回条数，可为空，默认 5") Integer vectorTopK,
                                        @ToolParam(description = "向量相似度阈值，可为空，默认 0.5") Double similarityThreshold) {
        logToolStart(TOOL_NAME, "query", query);
        try {
            validateQuery(query);
            Long userId = requireCurrentUserId();
            Long effectiveKnowledgeId = resolveKnowledgeId(knowledgeId);
            String formatted = formatSearchResults(
                    "hybrid",
                    effectiveKnowledgeId,
                    query,
                    knowledgeService.hybridSearch(
                            userId,
                            effectiveKnowledgeId,
                            query.trim(),
                            keywordSize == null ? 5 : keywordSize,
                            Boolean.TRUE.equals(useSmartAnalyzer),
                            vectorTopK == null ? 5 : vectorTopK,
                            similarityThreshold == null ? 0.5d : similarityThreshold)
            );
            logToolSuccess(TOOL_NAME, query.trim(), formatted);
            return formatted;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, query, e, "知识库混合检索执行失败：");
        }
    }
}
