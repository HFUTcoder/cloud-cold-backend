package com.shenchen.cloudcoldagent.service.chat.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryImageRelationMapper;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatMemoryHistoryImageRelation;
import com.shenchen.cloudcoldagent.model.entity.knowledge.KnowledgeDocumentImage;
import com.shenchen.cloudcoldagent.model.vo.agent.ChatMemoryHistoryVO;
import com.shenchen.cloudcoldagent.model.vo.agent.RetrievedKnowledgeImage;
import com.shenchen.cloudcoldagent.service.chat.ChatMemoryHistoryImageRelationService;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import com.shenchen.cloudcoldagent.service.chat.ChatMemoryHistoryService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentImageService;
import com.shenchen.cloudcoldagent.service.storage.MinioService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 聊天记忆服务层实现
 */
@Service
public class ChatMemoryHistoryServiceImpl extends ServiceImpl<ChatMemoryHistoryMapper, ChatMemoryHistory>
        implements ChatMemoryHistoryService {

    private final ChatConversationService chatConversationService;
    private final ChatMemoryHistoryImageRelationService chatMemoryHistoryImageRelationService;
    private final KnowledgeDocumentImageService knowledgeDocumentImageService;
    private final MinioService minioService;
    private final UserLongTermMemoryService userLongTermMemoryService;

    public ChatMemoryHistoryServiceImpl(ChatConversationService chatConversationService,
                                        ChatMemoryHistoryImageRelationService chatMemoryHistoryImageRelationService,
                                        KnowledgeDocumentImageService knowledgeDocumentImageService,
                                        MinioService minioService,
                                        UserLongTermMemoryService userLongTermMemoryService) {
        this.chatConversationService = chatConversationService;
        this.chatMemoryHistoryImageRelationService = chatMemoryHistoryImageRelationService;
        this.knowledgeDocumentImageService = knowledgeDocumentImageService;
        this.minioService = minioService;
        this.userLongTermMemoryService = userLongTermMemoryService;
    }

    @Override
    public List<ChatMemoryHistoryVO> listByConversationId(Long userId, String conversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        String normalizedConversationId = chatConversationService.normalizeConversationId(userId, conversationId);
        boolean owned = chatConversationService.isConversationOwnedByUser(userId, normalizedConversationId);
        if (!owned) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该会话");
        }

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0)
                .orderBy("createTime", true)
                .orderBy("id", true);
        List<ChatMemoryHistory> histories = this.mapper.selectListByQuery(queryWrapper);
        return buildHistoryVOs(histories);
    }

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        return chatConversationService.listConversationIdsByUserId(userId);
    }

    @Override
    public boolean deleteByHistoryId(Long userId, Long id) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不合法");
        }

        ChatMemoryHistory history = this.mapper.selectOneByQuery(QueryWrapper.create().eq("id", id).eq("isDelete", 0));
        if (history == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "记录不存在");
        }

        boolean owned = chatConversationService.isConversationOwnedByUser(userId, history.getConversationId());
        if (!owned) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除该消息");
        }
        ChatMemoryHistory updating = ChatMemoryHistory.builder()
                .isDelete(1)
                .build();
        boolean deleted = this.mapper.updateByQuery(
                updating,
                QueryWrapper.create()
                        .eq("id", id)
                        .eq("isDelete", 0)
        ) > 0;
        if (deleted) {
            userLongTermMemoryService.onHistoryDeleted(userId, history.getConversationId());
        }
        return deleted;
    }

    /**
     * 将历史消息实体列表转换成前端使用的 VO，并补齐命中的知识库图片。
     *
     * @param histories 历史消息实体列表。
     * @return 历史消息 VO 列表。
     */
    private List<ChatMemoryHistoryVO> buildHistoryVOs(List<ChatMemoryHistory> histories) {
        if (histories == null || histories.isEmpty()) {
            return List.of();
        }
        List<Long> historyIds = histories.stream()
                .map(ChatMemoryHistory::getId)
                .filter(id -> id != null && id > 0)
                .toList();
        Map<Long, List<ChatMemoryHistoryImageRelation>> relationMap =
                chatMemoryHistoryImageRelationService.mapByHistoryIds(historyIds);
        Map<Long, KnowledgeDocumentImage> imageMap = buildKnowledgeImageMap(relationMap);

        List<ChatMemoryHistoryVO> results = new ArrayList<>(histories.size());
        for (ChatMemoryHistory history : histories) {
            ChatMemoryHistoryVO vo = new ChatMemoryHistoryVO();
            BeanUtils.copyProperties(history, vo);
            vo.setRetrievedImages(buildRetrievedImages(history, relationMap, imageMap));
            results.add(vo);
        }
        return results;
    }

    /**
     * 根据历史消息图片关系批量加载图片实体映射。
     *
     * @param relationMap historyId 到图片关系列表的映射。
     * @return imageId 到图片实体的映射。
     */
    private Map<Long, KnowledgeDocumentImage> buildKnowledgeImageMap(
            Map<Long, List<ChatMemoryHistoryImageRelation>> relationMap) {
        if (relationMap == null || relationMap.isEmpty()) {
            return Map.of();
        }
        Set<Long> imageIds = relationMap.values().stream()
                .flatMap(List::stream)
                .map(ChatMemoryHistoryImageRelation::getImageId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeDocumentImage> images = knowledgeDocumentImageService.listByImageIds(new ArrayList<>(imageIds));
        Map<Long, KnowledgeDocumentImage> imageMap = new LinkedHashMap<>();
        for (KnowledgeDocumentImage image : images) {
            if (image != null && image.getId() != null) {
                imageMap.put(image.getId(), image);
            }
        }
        return imageMap;
    }

    /**
     * 为单条历史消息构建其命中的知识库图片列表。
     *
     * @param history 历史消息实体。
     * @param relationMap historyId 到图片关系列表的映射。
     * @param imageMap imageId 到图片实体的映射。
     * @return 命中的知识库图片列表。
     */
    private List<RetrievedKnowledgeImage> buildRetrievedImages(ChatMemoryHistory history,
                                                               Map<Long, List<ChatMemoryHistoryImageRelation>> relationMap,
                                                               Map<Long, KnowledgeDocumentImage> imageMap) {
        if (history == null || history.getId() == null || relationMap == null || relationMap.isEmpty()) {
            return List.of();
        }
        List<ChatMemoryHistoryImageRelation> relations = relationMap.get(history.getId());
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        List<RetrievedKnowledgeImage> results = new ArrayList<>(relations.size());
        for (ChatMemoryHistoryImageRelation relation : relations) {
            if (relation == null || relation.getImageId() == null) {
                continue;
            }
            KnowledgeDocumentImage image = imageMap.get(relation.getImageId());
            if (image == null || image.getId() == null || image.getObjectName() == null || image.getObjectName().isBlank()) {
                continue;
            }
            results.add(RetrievedKnowledgeImage.builder()
                    .imageId(image.getId())
                    .imageUrl(resolveAccessibleUrl(image))
                    .pageNumber(image.getPageNumber())
                    .documentId(image.getDocumentId())
                    .documentName(null)
                    .build());
        }
        return results;
    }

    /**
     * 为历史消息中的图片生成可访问地址；失败时回退到原始 URL。
     *
     * @param image 图片实体。
     * @return 可访问的图片 URL。
     */
    private String resolveAccessibleUrl(KnowledgeDocumentImage image) {
        try {
            return minioService.getPresignedUrl(image.getObjectName());
        } catch (Exception e) {
            return image.getImageUrl();
        }
    }
}
