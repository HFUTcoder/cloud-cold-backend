package com.shenchen.cloudcoldagent.tools.rag;

import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
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

    /**
     * 创建 `AbstractKnowledgeSearchTool` 实例。
     *
     * @param knowledgeService knowledgeService 参数。
     * @param chatConversationService chatConversationService 参数。
     */
    protected AbstractKnowledgeSearchTool(KnowledgeService knowledgeService,
                                          ChatConversationService chatConversationService) {
        super(false);
        this.knowledgeService = knowledgeService;
        this.chatConversationService = chatConversationService;
    }

    /**
     * 处理 `require Current User Id` 对应逻辑。
     *
     * @return 返回处理结果。
     */
    protected Long requireCurrentUserId() {
        Long userId = AgentRuntimeContext.getCurrentUserId();
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR, "当前工具缺少用户上下文");
        return userId;
    }

    /**
     * 校验 `validate Query` 对应内容。
     *
     * @param query query 参数。
     */
    protected void validateQuery(String query) {
        ThrowUtils.throwIf(query == null || query.isBlank(), ErrorCode.PARAMS_ERROR, "查询语句不能为空");
    }

    /**
     * 解析 `resolve Knowledge Id` 对应结果。
     *
     * @param requestedKnowledgeId requestedKnowledgeId 参数。
     * @return 返回处理结果。
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

    /**
     * 处理 `format Search Results` 对应逻辑。
     *
     * @param retrievalMode retrievalMode 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param query query 参数。
     * @param chunks chunks 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 处理 `append Metadata Value` 对应逻辑。
     *
     * @param sb sb 参数。
     * @param label label 参数。
     * @param value value 参数。
     */
    private void appendMetadataValue(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }
        sb.append(label).append("：").append(value).append("\n");
    }

    /**
     * 处理 `preview Content` 对应逻辑。
     *
     * @param content content 参数。
     * @return 返回处理结果。
     */
    private String previewContent(String content) {
        if (content == null || content.isBlank()) {
            return "无";
        }
        String normalized = content.replace("\r", " ").replace("\n", " ").trim().replaceAll("\\s+", " ");
        return truncateText(normalized, DEFAULT_CONTENT_PREVIEW_LIMIT);
    }

    /**
     * 处理 `first Non Null` 对应逻辑。
     *
     * @param first first 参数。
     * @param second second 参数。
     * @return 返回处理结果。
     */
    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    /**
     * 处理 `default Object Text` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    private String defaultObjectText(Object value) {
        return value == null ? "无" : defaultText(String.valueOf(value));
    }
}
