package com.shenchen.cloudcoldagent.service.knowledge.impl;

import com.shenchen.cloudcoldagent.constant.KnowledgeChunkConstant;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.prompts.KnowledgePrompts;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgePreprocessService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.storage.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库预处理服务实现，负责在进入 Agent 前执行预检索、拼装增强问题并提取命中图片。
 */
@Service
@Slf4j
public class KnowledgePreprocessServiceImpl implements KnowledgePreprocessService {

    private static final int MAX_CONTENT_SNIPPETS = 8;
    private static final int MAX_TOTAL_CONTENT_CHARS = 4000;

    private final KnowledgeService knowledgeService;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;
    private final MinioService minioService;

    /**
     * 注入知识库预处理链路所需的依赖服务。
     *
     * @param knowledgeService 知识库检索服务。
     * @param knowledgeDocumentImageService 知识库图片服务。
     * @param minioService MinIO 文件服务。
     */
    public KnowledgePreprocessServiceImpl(KnowledgeService knowledgeService,
                                          KnowledgeDocumentImageService knowledgeDocumentImageService,
                                          MinioService minioService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
        this.minioService = minioService;
    }

    /**
     * 对当前问题执行知识库预检索，并输出增强后的问题文本与命中图片。
     *
     * @param userId 当前用户 id。
     * @param conversation 当前会话。
     * @param question 用户原始问题。
     * @return 知识库预处理结果。
     */
    @Override
    public KnowledgePreprocessResult preprocess(Long userId, ChatConversation conversation, String question) {
        String safeQuestion = question == null ? "" : question.trim();
        if (userId == null || userId <= 0 || safeQuestion.isBlank()) {
            return new KnowledgePreprocessResult(question, List.of(), List.of(), false);
        }
        if (conversation == null || conversation.getSelectedKnowledgeId() == null || conversation.getSelectedKnowledgeId() <= 0) {
            return new KnowledgePreprocessResult(question, List.of(), List.of(), false);
        }

        Long knowledgeId = conversation.getSelectedKnowledgeId();
        try {
            List<EsDocumentChunk> parentChunks = knowledgeService.hybridSearch(userId, knowledgeId, safeQuestion);
            List<RetrievedKnowledgeImage> retrievedImages = extractRetrievedImagesFromParents(parentChunks);
            String effectiveQuestion = buildKnowledgeAugmentedQuestion(safeQuestion, parentChunks);
            log.info("知识库预检索完成，userId={}, conversationId={}, knowledgeId={}, questionLength={}, hitCount={}, imageHitCount={}, effectiveQuestionLength={}",
                    userId,
                    conversation.getConversationId(),
                    knowledgeId,
                    safeQuestion.length(),
                    parentChunks == null ? 0 : parentChunks.size(),
                    retrievedImages.size(),
                    effectiveQuestion.length());
            logRetrievedChunks(conversation.getConversationId(), safeQuestion, parentChunks);
            return new KnowledgePreprocessResult(
                    effectiveQuestion,
                    parentChunks == null ? List.of() : parentChunks,
                    retrievedImages,
                    true
            );
        } catch (Exception e) {
            log.warn("知识库预检索失败，回退原问题。userId={}, conversationId={}, knowledgeId={}, message={}",
                    userId,
                    conversation.getConversationId(),
                    knowledgeId,
                    e.getMessage(),
                    e);
            return new KnowledgePreprocessResult(question, List.of(), List.of(), true);
        }
    }

    /**
     * 将命中的知识片段拼进用户问题，构建供 Agent 使用的增强问题。
     *
     * @param question 用户原始问题。
     * @param chunks 预检索命中的 chunk 列表。
     * @return 增强后的问题文本。
     */
    private String buildKnowledgeAugmentedQuestion(String question, List<EsDocumentChunk> chunks) {
        List<String> snippets = collectContentSnippets(chunks);
        if (snippets.isEmpty()) {
            return KnowledgePrompts.buildNoHitAugmentedQuestion(question);
        }
        return KnowledgePrompts.buildHitAugmentedQuestion(String.join("\n\n", snippets), question);
    }

    /**
     * 从命中 chunk 中收集可注入提示词的正文片段，并做去重与长度控制。
     *
     * @param chunks 命中的 chunk 列表。
     * @return 用于增强问题的片段列表。
     */
    private List<String> collectContentSnippets(List<EsDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        int totalChars = 0;
        for (EsDocumentChunk chunk : chunks) {
            if (chunk == null || StringUtils.isBlank(chunk.getContent())) {
                continue;
            }
            String content = normalizeContent(chunk.getContent());
            if (content.isBlank() || !deduplicated.add(content)) {
                continue;
            }
            totalChars += content.length();
            if (deduplicated.size() >= MAX_CONTENT_SNIPPETS || totalChars >= MAX_TOTAL_CONTENT_CHARS) {
                break;
            }
        }
        return new ArrayList<>(deduplicated);
    }

    /**
     * 规范化 chunk 正文内容，统一换行并去掉首尾空白。
     *
     * @param content 原始内容。
     * @return 规范化后的文本。
     */
    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r", "\n").trim();
    }

    /**
     * 从父块 metadata 中提取关联的图像信息。
     *
     * @param parentChunks 解析后的父块列表。
     * @return 命中图片列表。
     */
    private List<RetrievedKnowledgeImage> extractRetrievedImagesFromParents(List<EsDocumentChunk> parentChunks) {
        if (parentChunks == null || parentChunks.isEmpty()) {
            return List.of();
        }

        Set<Long> allImageIds = new LinkedHashSet<>();
        for (EsDocumentChunk parent : parentChunks) {
            if (parent == null || parent.getMetadata() == null) {
                continue;
            }
            Object imageIdsObj = parent.getMetadata().get(KnowledgeChunkConstant.META_IMAGE_IDS);
            if (imageIdsObj instanceof List<?> list) {
                for (Object item : list) {
                    Long id = toLong(item);
                    if (id != null && id > 0) {
                        allImageIds.add(id);
                    }
                }
            } else if (imageIdsObj != null) {
                Long id = toLong(imageIdsObj);
                if (id != null && id > 0) {
                    allImageIds.add(id);
                }
            }
        }

        if (allImageIds.isEmpty()) {
            return List.of();
        }

        List<KnowledgeDocumentImage> images = knowledgeDocumentImageService.listByImageIds(new ArrayList<>(allImageIds));
        Map<Long, String> documentNameMap = collectDocumentNamesFromParents(parentChunks);

        List<RetrievedKnowledgeImage> results = new ArrayList<>(images.size());
        for (KnowledgeDocumentImage image : images) {
            if (image == null || image.getId() == null || StringUtils.isBlank(image.getObjectName())) {
                continue;
            }
            String accessibleUrl = resolveAccessibleImageUrl(image);
            if (StringUtils.isBlank(accessibleUrl)) {
                continue;
            }
            results.add(RetrievedKnowledgeImage.builder()
                    .imageId(image.getId())
                    .imageUrl(accessibleUrl)
                    .pageNumber(image.getPageNumber())
                    .documentId(image.getDocumentId())
                    .documentName(documentNameMap.get(image.getId()))
                    .build());
        }
        return results;
    }

    /**
     * 为命中的图片生成可访问地址；失败时回退为数据库中的原始地址。
     *
     * @param image 图片记录。
     * @return 可访问的图片 URL。
     */
    private String resolveAccessibleImageUrl(KnowledgeDocumentImage image) {
        if (image == null || StringUtils.isBlank(image.getObjectName())) {
            return null;
        }
        try {
            return minioService.getPresignedUrl(image.getObjectName());
        } catch (Exception e) {
            log.warn("生成知识库命中图片预签名链接失败，imageId={}, objectName={}, message={}",
                    image.getId(),
                    image.getObjectName(),
                    e.getMessage(),
                    e);
            return image.getImageUrl();
        }
    }

    /**
     * 从父块 metadata 中提取图像所属文档名。
     *
     * @param parentChunks 父块列表。
     * @return imageId 到文档名的映射。
     */
    private Map<Long, String> collectDocumentNamesFromParents(List<EsDocumentChunk> parentChunks) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (EsDocumentChunk parent : parentChunks) {
            if (parent == null || parent.getMetadata() == null) {
                continue;
            }
            String docName = defaultObjectText(parent.getMetadata().get(KnowledgeChunkConstant.META_DOCUMENT_NAME));
            if ("无".equals(docName)) {
                docName = defaultObjectText(parent.getMetadata().get(KnowledgeChunkConstant.META_FILE_NAME));
            }
            Object imageIdsObj = parent.getMetadata().get(KnowledgeChunkConstant.META_IMAGE_IDS);
            if (imageIdsObj instanceof List<?> list) {
                for (Object item : list) {
                    Long id = toLong(item);
                    if (id != null && id > 0) {
                        result.putIfAbsent(id, docName);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 将元数据里的任意值安全转换成 Long。
     *
     * @param value 原始值。
     * @return 转换后的 Long；无法转换时返回 null。
     */
    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 输出知识库预检索命中明细，方便排查命中质量。
     *
     * @param conversationId 当前会话 id。
     * @param query 用户查询文本。
     * @param chunks 命中的 chunk 列表。
     */
    private void logRetrievedChunks(String conversationId, String query, List<EsDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.info("知识库预检索命中明细为空，conversationId={}, query={}", conversationId, query);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("知识库预检索命中明细，conversationId=")
                .append(conversationId)
                .append(", query=")
                .append(query)
                .append(", hitCount=")
                .append(chunks.size())
                .append('\n');
        for (int i = 0; i < chunks.size(); i++) {
            EsDocumentChunk chunk = chunks.get(i);
            Map<String, Object> metadata = chunk == null || chunk.getMetadata() == null
                    ? Map.of()
                    : new LinkedHashMap<>(chunk.getMetadata());
            sb.append(i + 1)
                    .append(". chunkId=")
                    .append(defaultObjectText(firstNonNull(metadata.get(KnowledgeChunkConstant.META_CHUNK_ID), chunk == null ? null : chunk.getId())))
                    .append(", chunkType=")
                    .append(defaultObjectText(metadata.get(KnowledgeChunkConstant.META_CHUNK_TYPE)))
                    .append(", keyword_score=")
                    .append(defaultObjectText(metadata.get("keyword_score")))
                    .append(", vector_score=")
                    .append(defaultObjectText(metadata.get("vector_score")))
                    .append(", hybrid_score=")
                    .append(defaultObjectText(metadata.get("hybrid_score")))
                    .append(", scalar_rank=")
                    .append(defaultObjectText(metadata.get("scalar_rank")))
                    .append(", vector_rank=")
                    .append(defaultObjectText(metadata.get("vector_rank")))
                    .append(", contentSnippet=")
                    .append(abbreviate(normalizeContent(chunk == null ? null : chunk.getContent()).replace('\n', ' '), 220))
                    .append('\n');
        }
        log.info(sb.toString());
    }

    /**
     * 返回两个值中的第一个非空对象。
     *
     * @param first 首选值。
     * @param second 备选值。
     * @return 第一个非空对象；若都为空则返回 null。
     */
    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    /**
     * 将任意对象转换成日志中可展示的文本。
     *
     * @param value 原始对象。
     * @return 可读文本；为空时返回“无”。
     */
    private String defaultObjectText(Object value) {
        return value == null ? "无" : String.valueOf(value);
    }

    /**
     * 截断日志中的长文本片段。
     *
     * @param text 原始文本。
     * @param maxLength 最大长度。
     * @return 截断后的文本。
     */
    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
