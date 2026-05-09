package com.shenchen.cloudcoldagent.tools.rag;

import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `KnowledgeScalarSearchTool` 类型实现。
 */
@Component
public class KnowledgeScalarSearchTool extends AbstractKnowledgeSearchTool {

    private static final String TOOL_NAME = "knowledge_scalar_search";

    /**
     * 创建 `KnowledgeScalarSearchTool` 实例。
     *
     * @param knowledgeService knowledgeService 参数。
     * @param chatConversationService chatConversationService 参数。
     */
    public KnowledgeScalarSearchTool(KnowledgeService knowledgeService,
                                     ChatConversationService chatConversationService) {
        super(knowledgeService, chatConversationService);
    }

    /**
     * 处理 `knowledge Scalar Search` 对应逻辑。
     *
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @param size size 参数。
     * @param useSmartAnalyzer useSmartAnalyzer 参数。
     * @return 返回处理结果。
     */
    @Tool(name = TOOL_NAME, description = "在指定知识库中执行关键词/标量检索，适合查找术语、编号、精确表述；未传 knowledgeId 时默认使用当前会话已绑定知识库")
    public String knowledgeScalarSearch(@ToolParam(description = "知识库 ID，可为空；为空时默认使用当前会话已绑定知识库") Long knowledgeId,
                                        @ToolParam(description = "查询语句") String query,
                                        @ToolParam(description = "返回条数，可为空，默认 5") Integer size,
                                        @ToolParam(description = "是否启用智能分词，可为空，默认 false") Boolean useSmartAnalyzer) {
        logToolStart(TOOL_NAME, "query", query);
        try {
            validateQuery(query);
            Long userId = requireCurrentUserId();
            Long effectiveKnowledgeId = resolveKnowledgeId(knowledgeId);
            String formatted = formatSearchResults(
                    "scalar",
                    effectiveKnowledgeId,
                    query,
                    knowledgeService.scalarSearch(
                            userId,
                            effectiveKnowledgeId,
                            query.trim(),
                            size == null ? 5 : size,
                            Boolean.TRUE.equals(useSmartAnalyzer))
            );
            logToolSuccess(TOOL_NAME, query.trim(), formatted);
            return formatted;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, query, e, "知识库标量检索执行失败：");
        }
    }
}
