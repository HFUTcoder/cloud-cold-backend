package com.shenchen.cloudcoldagent.tools.rag;

import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatConversation;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具基类，统一处理上下文校验与结果格式化。
 */
public abstract class AbstractKnowledgeSearchTool extends BaseTool {

    private static final int DEFAULT_CONTENT_PREVIEW_LIMIT = 220;

    protected final KnowledgeService knowledgeService;

    protected final ChatConversationService chatConversationService;

    protected AbstractKnowledgeSearchTool(KnowledgeService knowledgeService,
                                          ChatConversationService chatConversationService) {
        super(false);
        this.knowledgeService = knowledgeService;
        this.chatConversationService = chatConversationService;
    }

    protected Long requireCurrentUserId() {
        Long userId = AgentRuntimeContext.getCurrentUserId();
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR, "当前工具缺少用户上下文");
        return userId;
    }

    protected void validateQuery(String query) {
        ThrowUtils.throwIf(query == null || query.isBlank(), ErrorCode.PARAMS_ERROR, "查询语句不能为空");
    }

    /**
     * 解析知识库 ID：优先使用显式传入值，否则从当前会话绑定中获取。
     */
    protected Long resolveKnowledgeId(Long requestedKnowledgeId) {
        if (requestedKnowledgeId != null && requestedKnowledgeId > 0) {
            return requestedKnowledgeId;
        }
        Long userId = requireCurrentUserId();
        String conversationId = AgentRuntimeContext.getCurrentConversationId();
        ThrowUtils.throwIf(conversationId == null || conversationId.isBlank(),
                ErrorCode.PARAMS_ERROR, "当前工具缺少会话上下文，且未显式提供知识库 id");
        ChatConversation conversation = chatConversationService.getByConversationId(userId, conversationId);
        Long boundKnowledgeId = conversation == null ? null : conversation.getSelectedKnowledgeId();
        ThrowUtils.throwIf(boundKnowledgeId == null || boundKnowledgeId <= 0,
                ErrorCode.PARAMS_ERROR, "当前会话未绑定知识库，请先绑定知识库后再提问");
        return boundKnowledgeId;
    }

    protected String formatSearchResults(String retrievalMode, Long knowledgeId, String query, List<EsDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return """
                    未检索到相关知识片段。
                    检索方式：%s
                    知识库ID：%s
                    查询词：%s
                    """.formatted(retrievalMode, knowledgeId, defaultText(query));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果\n")
                .append("检索方式：").append(retrievalMode).append("\n")
                .append("知识库ID：").append(knowledgeId).append("\n")
                .append("查询词：").append(defaultText(query)).append("\n")
                .append("命中数量：").append(chunks.size()).append("\n");

        for (int i = 0; i < chunks.size(); i++) {
            EsDocumentChunk chunk = chunks.get(i);
            Map<String, Object> metadata = chunk == null || chunk.getMetadata() == null
                    ? Map.of()
                    : new LinkedHashMap<>(chunk.getMetadata());
            sb.append("\n")
                    .append(i + 1)
                    .append(". chunkId：")
                    .append(defaultObjectText(firstNonNull(metadata.get("chunkId"), chunk == null ? null : chunk.getId())))
                    .append("\n")
                    .append("文档ID：")
                    .append(defaultObjectText(metadata.get("documentId")))
                    .append("\n")
                    .append("文档名：")
                    .append(defaultObjectText(firstNonNull(metadata.get("documentName"), metadata.get("fileName"))))
                    .append("\n")
                    .append("来源：")
                    .append(defaultObjectText(metadata.get("source")))
                    .append("\n");

            appendMetadataValue(sb, "向量分数", metadata.get("vector_score"));
            appendMetadataValue(sb, "混合分数", metadata.get("hybrid_score"));
            appendMetadataValue(sb, "关键词排序", metadata.get("scalar_rank"));
            appendMetadataValue(sb, "向量排序", metadata.get("vector_rank"));
            sb.append("片段内容：").append(previewContent(chunk == null ? null : chunk.getContent())).append("\n");
        }
        return sb.toString();
    }

    private void appendMetadataValue(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }
        sb.append(label).append("：").append(value).append("\n");
    }

    private String previewContent(String content) {
        if (content == null || content.isBlank()) {
            return "无";
        }
        String normalized = content.replace("\r", " ").replace("\n", " ").trim().replaceAll("\\s+", " ");
        return truncateText(normalized, DEFAULT_CONTENT_PREVIEW_LIMIT);
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private String defaultObjectText(Object value) {
        return value == null ? "无" : defaultText(String.valueOf(value));
    }
}
