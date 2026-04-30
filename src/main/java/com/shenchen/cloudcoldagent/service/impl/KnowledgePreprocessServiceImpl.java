package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.record.agent.knowledge.KnowledgePreprocessResult;
import com.shenchen.cloudcoldagent.model.entity.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.vo.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.KnowledgePreprocessService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

@Service
@Slf4j
public class KnowledgePreprocessServiceImpl implements KnowledgePreprocessService {

    private static final int MAX_CONTENT_SNIPPETS = 8;
    private static final int MAX_TOTAL_CONTENT_CHARS = 4000;

    private final KnowledgeService knowledgeService;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;
    private final MinioService minioService;

    public KnowledgePreprocessServiceImpl(KnowledgeService knowledgeService,
                                          KnowledgeDocumentImageService knowledgeDocumentImageService,
                                          MinioService minioService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
        this.minioService = minioService;
    }

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
            List<EsDocumentChunk> chunks = knowledgeService.hybridSearch(userId, knowledgeId, safeQuestion);
            List<RetrievedKnowledgeImage> retrievedImages = extractRetrievedImages(chunks);
            String effectiveQuestion = buildKnowledgeAugmentedQuestion(safeQuestion, chunks);
            log.info("知识库预检索完成，userId={}, conversationId={}, knowledgeId={}, questionLength={}, hitCount={}, imageHitCount={}, effectiveQuestionLength={}",
                    userId,
                    conversation.getConversationId(),
                    knowledgeId,
                    safeQuestion.length(),
                    chunks == null ? 0 : chunks.size(),
                    retrievedImages.size(),
                    effectiveQuestion.length());
            logRetrievedChunks(conversation.getConversationId(), safeQuestion, chunks);
            return new KnowledgePreprocessResult(
                    effectiveQuestion,
                    chunks == null ? List.of() : chunks,
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

    private String buildKnowledgeAugmentedQuestion(String question, List<EsDocumentChunk> chunks) {
        List<String> snippets = collectContentSnippets(chunks);
        if (snippets.isEmpty()) {
            return """
                    请优先基于知识库内容回答用户问题。
                    当前会话已绑定知识库，但本次预检索没有命中可直接使用的知识内容。
                    如果无法仅根据知识库确定答案，请明确说明，不要编造。

                    【用户问题】
                    %s
                    """.formatted(question);
        }
        return """
                请优先基于以下知识库内容回答用户问题。
                如果知识库内容不足以支持结论，请明确说明，不要编造。

                【知识库内容】
                %s

                【用户问题】
                %s
                """.formatted(String.join("\n\n", snippets), question);
    }

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

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r", "\n").trim();
    }

    private List<RetrievedKnowledgeImage> extractRetrievedImages(List<EsDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Long> imageIds = collectImageIds(chunks);
        if (imageIds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeDocumentImage> images = knowledgeDocumentImageService.listByImageIds(imageIds);
        Map<Long, String> documentNameMap = collectDocumentNamesByImageId(chunks);
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

    private List<Long> collectImageIds(List<EsDocumentChunk> chunks) {
        Set<Long> imageIds = new LinkedHashSet<>();
        for (EsDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isEmpty()) {
                continue;
            }
            if (!isImageDescriptionChunk(chunk)) {
                continue;
            }
            Object parentId = chunk.getMetadata().get("parentId");
            Long imageId = toLong(parentId);
            if (imageId != null && imageId > 0) {
                imageIds.add(imageId);
            }
        }
        return new ArrayList<>(imageIds);
    }

    private Map<Long, String> collectDocumentNamesByImageId(List<EsDocumentChunk> chunks) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (EsDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isEmpty()) {
                continue;
            }
            Long imageId = toLong(chunk.getMetadata().get("parentId"));
            if (imageId == null || imageId <= 0 || result.containsKey(imageId)) {
                continue;
            }
            Object documentName = chunk.getMetadata().get("documentName");
            if (documentName == null || StringUtils.isBlank(String.valueOf(documentName))) {
                documentName = chunk.getMetadata().get("fileName");
            }
            result.put(imageId, documentName == null ? null : String.valueOf(documentName));
        }
        return result;
    }

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

    private boolean isImageDescriptionChunk(EsDocumentChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isEmpty()) {
            return false;
        }
        Object chunkType = chunk.getMetadata().get("chunkType");
        return chunkType != null && Objects.equals("IMAGE_DESCRIPTION", String.valueOf(chunkType));
    }

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
                    .append(defaultObjectText(firstNonNull(metadata.get("chunkId"), chunk == null ? null : chunk.getId())))
                    .append(", chunkType=")
                    .append(defaultObjectText(metadata.get("chunkType")))
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

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private String defaultObjectText(Object value) {
        return value == null ? "无" : String.valueOf(value);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
