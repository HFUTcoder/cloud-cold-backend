package com.shenchen.cloudcoldagent.service.chat.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryImageRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.agent.ChatMemoryHistoryImageRelation;
import com.shenchen.cloudcoldagent.service.chat.ChatMemoryHistoryImageRelationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 历史消息-知识库图片关系服务实现，负责绑定和查询历史消息关联的图片。
 */
@Service
public class ChatMemoryHistoryImageRelationServiceImpl
        extends ServiceImpl<ChatMemoryHistoryImageRelationMapper, ChatMemoryHistoryImageRelation>
        implements ChatMemoryHistoryImageRelationService {

    /**
     * 为某条历史消息重新绑定命中的知识库图片列表。
     *
     * @param historyId 历史消息 id。
     * @param conversationId 会话 id。
     * @param imageIds 图片 id 列表。
     */
    @Override
    public void bindImagesToHistory(Long historyId, String conversationId, List<Long> imageIds) {
        validateHistoryId(historyId);
        validateConversationId(conversationId);
        deleteByHistoryId(historyId);
        if (imageIds == null || imageIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ChatMemoryHistoryImageRelation> relations = new ArrayList<>();
        int sortOrder = 0;
        for (Long imageId : imageIds) {
            if (imageId == null || imageId <= 0) {
                continue;
            }
            relations.add(ChatMemoryHistoryImageRelation.builder()
                    .historyId(historyId)
                    .conversationId(conversationId.trim())
                    .imageId(imageId)
                    .sortOrder(sortOrder++)
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build());
        }
        if (!relations.isEmpty()) {
            this.saveBatch(relations);
        }
    }

    @Override
    public List<ChatMemoryHistoryImageRelation> listByHistoryId(Long historyId) {
        validateHistoryId(historyId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                .eq("historyId", historyId)
                .eq("isDelete", 0)
                .orderBy("sortOrder", true)
                .orderBy("id", true));
    }

    /**
     * 按历史消息 id 批量查询图片关系，并按 historyId 分组。
     *
     * @param historyIds 历史消息 id 列表。
     * @return historyId 到图片关系列表的映射。
     */
    @Override
    public Map<Long, List<ChatMemoryHistoryImageRelation>> mapByHistoryIds(List<Long> historyIds) {
        if (historyIds == null || historyIds.isEmpty()) {
            return Map.of();
        }
        List<Long> normalizedIds = historyIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        List<ChatMemoryHistoryImageRelation> relations = this.mapper.selectListByQuery(QueryWrapper.create()
                .in("historyId", normalizedIds)
                .eq("isDelete", 0)
                .orderBy("historyId", true)
                .orderBy("sortOrder", true)
                .orderBy("id", true));
        Map<Long, List<ChatMemoryHistoryImageRelation>> result = new LinkedHashMap<>();
        for (ChatMemoryHistoryImageRelation relation : relations) {
            if (relation == null || relation.getHistoryId() == null) {
                continue;
            }
            result.computeIfAbsent(relation.getHistoryId(), _key -> new ArrayList<>()).add(relation);
        }
        return result;
    }

    @Override
    public boolean deleteByHistoryId(Long historyId) {
        validateHistoryId(historyId);
        return this.mapper.updateByQuery(
                ChatMemoryHistoryImageRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("historyId", historyId)
                        .eq("isDelete", 0)
        ) > 0;
    }

    @Override
    public boolean deleteByConversationId(String conversationId) {
        validateConversationId(conversationId);
        return this.mapper.updateByQuery(
                ChatMemoryHistoryImageRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        ) > 0;
    }

    private void validateHistoryId(Long historyId) {
        if (historyId == null || historyId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "historyId 不合法");
        }
    }

    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
    }
}
