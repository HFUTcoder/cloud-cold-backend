package com.shenchen.cloudcoldagent.service.usermemory.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.mapper.UserLongTermMemoryConversationStateMapper;
import com.shenchen.cloudcoldagent.mapper.UserLongTermMemoryMapper;
import com.shenchen.cloudcoldagent.mapper.UserLongTermMemorySourceRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryConversationState;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 长期记忆元数据服务实现，负责记忆实体、来源关系和会话处理状态的持久化维护。
 */
@Service
public class UserLongTermMemoryMetadataServiceImpl implements UserLongTermMemoryMetadataService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";
    private static final String CONVERSATION_STATUS_PROCESSED = "PROCESSED";
    private static final String CONVERSATION_STATUS_UNPROCESSED = "UNPROCESSED";

    private final UserLongTermMemoryMapper userLongTermMemoryMapper;
    private final UserLongTermMemorySourceRelationMapper sourceRelationMapper;
    private final UserLongTermMemoryConversationStateMapper conversationStateMapper;

    /**
     * 创建 `UserLongTermMemoryMetadataServiceImpl` 实例。
     *
     * @param userLongTermMemoryMapper userLongTermMemoryMapper 参数。
     * @param sourceRelationMapper sourceRelationMapper 参数。
     * @param conversationStateMapper conversationStateMapper 参数。
     */
    public UserLongTermMemoryMetadataServiceImpl(UserLongTermMemoryMapper userLongTermMemoryMapper,
                                                 UserLongTermMemorySourceRelationMapper sourceRelationMapper,
                                                 UserLongTermMemoryConversationStateMapper conversationStateMapper) {
        this.userLongTermMemoryMapper = userLongTermMemoryMapper;
        this.sourceRelationMapper = sourceRelationMapper;
        this.conversationStateMapper = conversationStateMapper;
    }

    /**
     * 批量写入提炼后的长期记忆实体及其来源关系。
     *
     * @param userId 当前用户 id。
     * @param conversationId 来源会话 id。
     * @param memories 待写入的长期记忆文档列表。
     */
    @Override
    public void upsertMemories(Long userId, String conversationId, List<UserLongTermMemoryDoc> memories) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId) || memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (UserLongTermMemoryDoc memory : memories) {
            if (memory == null || StringUtils.isBlank(memory.getId())) {
                continue;
            }
            userLongTermMemoryMapper.insert(UserLongTermMemory.builder()
                    .memoryId(memory.getId())
                    .userId(userId)
                    .memoryType(memory.getMemoryType())
                    .title(memory.getTitle())
                    .content(memory.getContent())
                    .summary(memory.getSummary())
                    .confidence(memory.getConfidence())
                    .importance(memory.getImportance())
                    .originConversationId(conversationId)
                    .status(STATUS_ACTIVE)
                    .lastRetrievedAt(null)
                    .lastReinforcedAt(now)
                    .createTime(memory.getCreatedAt() == null ? now : memory.getCreatedAt())
                    .updateTime(memory.getUpdatedAt() == null ? now : memory.getUpdatedAt())
                    .isDelete(0)
                    .build());

            if (memory.getSourceHistoryIds() == null || memory.getSourceHistoryIds().isEmpty()) {
                continue;
            }
            Map<Long, String> conversationByHistoryId = memory.getSourceConversationsByHistoryId() == null
                    ? Map.of()
                    : memory.getSourceConversationsByHistoryId();
            for (Long historyId : memory.getSourceHistoryIds()) {
                if (historyId == null || historyId <= 0) {
                    continue;
                }
                sourceRelationMapper.insert(UserLongTermMemorySourceRelation.builder()
                        .memoryId(memory.getId())
                        .userId(userId)
                        .conversationId(conversationByHistoryId.get(historyId))
                        .historyId(historyId)
                        .createTime(now)
                        .updateTime(now)
                        .isDelete(0)
                        .build());
            }
        }
    }

    /**
     * 查询 `list Active By User Id` 对应集合。
     *
     * @param userId userId 参数。
     * @param size size 参数。
     * @return 返回处理结果。
     */
    @Override
    public List<UserLongTermMemory> listActiveByUserId(Long userId, int size) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return userLongTermMemoryMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .eq("status", STATUS_ACTIVE)
                .orderBy("updateTime", false)
                .limit(size));
    }

    /**
     * 按 memoryId 批量查询仍处于激活状态的长期记忆实体。
     *
     * @param userId 当前用户 id。
     * @param memoryIds 记忆 id 列表。
     * @return memoryId 到实体的映射。
     */
    @Override
    public Map<String, UserLongTermMemory> mapActiveByMemoryIds(Long userId, List<String> memoryIds) {
        if (userId == null || userId <= 0 || memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        List<UserLongTermMemory> rows = userLongTermMemoryMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .in("memoryId", memoryIds)
                .eq("isDelete", 0)
                .eq("status", STATUS_ACTIVE));
        Map<String, UserLongTermMemory> result = new LinkedHashMap<>();
        for (UserLongTermMemory row : rows) {
            if (row == null || row.getMemoryId() == null) {
                continue;
            }
            result.put(row.getMemoryId(), row);
        }
        return result;
    }

    /**
     * 按 memoryId 批量查询激活记忆，并组装成向量存储层使用的文档对象。
     *
     * @param userId 当前用户 id。
     * @param memoryIds 记忆 id 列表。
     * @return memoryId 到长期记忆文档的映射。
     */
    @Override
    public Map<String, UserLongTermMemoryDoc> mapActiveDocsByMemoryIds(Long userId, List<String> memoryIds) {
        if (userId == null || userId <= 0 || memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, UserLongTermMemory> memoryMap = mapActiveByMemoryIds(userId, memoryIds);
        if (memoryMap.isEmpty()) {
            return Map.of();
        }
        Map<String, List<UserLongTermMemorySourceRelation>> sourceMap = mapSourcesByMemoryIds(new ArrayList<>(memoryMap.keySet()));
        Map<String, UserLongTermMemoryDoc> result = new LinkedHashMap<>();
        for (String memoryId : memoryIds) {
            UserLongTermMemory memory = memoryMap.get(memoryId);
            if (memory == null || StringUtils.isBlank(memory.getMemoryId())) {
                continue;
            }
            List<UserLongTermMemorySourceRelation> relations = sourceMap.getOrDefault(memory.getMemoryId(), List.of());
            List<Long> sourceHistoryIds = relations.stream()
                    .map(UserLongTermMemorySourceRelation::getHistoryId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            List<String> sourceConversationIds = relations.stream()
                    .map(UserLongTermMemorySourceRelation::getConversationId)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
            Map<Long, String> sourceConversationsByHistoryId = new LinkedHashMap<>();
            for (UserLongTermMemorySourceRelation relation : relations) {
                if (relation == null || relation.getHistoryId() == null || StringUtils.isBlank(relation.getConversationId())) {
                    continue;
                }
                sourceConversationsByHistoryId.put(relation.getHistoryId(), relation.getConversationId());
            }
            result.put(memory.getMemoryId(), UserLongTermMemoryDoc.builder()
                    .id(memory.getMemoryId())
                    .userId(memory.getUserId())
                    .memoryType(memory.getMemoryType())
                    .title(memory.getTitle())
                    .content(memory.getContent())
                    .summary(memory.getSummary())
                    .embeddingText(memory.getContent())
                    .confidence(memory.getConfidence())
                    .importance(memory.getImportance())
                    .originConversationId(memory.getOriginConversationId())
                    .sourceConversationIds(sourceConversationIds)
                    .sourceHistoryIds(sourceHistoryIds)
                    .sourceConversationsByHistoryId(sourceConversationsByHistoryId)
                    .createdAt(memory.getCreateTime())
                    .updatedAt(memory.getUpdateTime())
                    .build());
        }
        return result;
    }

    /**
     * 批量查询多个记忆对应的来源关系。
     *
     * @param memoryIds 记忆 id 列表。
     * @return memoryId 到来源关系列表的映射。
     */
    @Override
    public Map<String, List<UserLongTermMemorySourceRelation>> mapSourcesByMemoryIds(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        List<UserLongTermMemorySourceRelation> rows = sourceRelationMapper.selectListByQuery(QueryWrapper.create()
                .in("memoryId", memoryIds)
                .eq("isDelete", 0)
                .orderBy("id", true));
        Map<String, List<UserLongTermMemorySourceRelation>> result = new LinkedHashMap<>();
        for (UserLongTermMemorySourceRelation row : rows) {
            if (row == null || row.getMemoryId() == null) {
                continue;
            }
            result.computeIfAbsent(row.getMemoryId(), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /**
     * 更新一批长期记忆的最近召回时间。
     *
     * @param userId 当前用户 id。
     * @param memoryIds 被召回的记忆 id 列表。
     */
    @Override
    public void markRetrieved(Long userId, List<String> memoryIds) {
        if (userId == null || userId <= 0 || memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        userLongTermMemoryMapper.updateByQuery(
                UserLongTermMemory.builder()
                        .lastRetrievedAt(LocalDateTime.now())
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .in("memoryId", memoryIds)
                        .eq("isDelete", 0)
        );
    }

    /**
     * 删除 `delete Memory` 对应内容。
     *
     * @param userId userId 参数。
     * @param memoryId memoryId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean deleteMemory(Long userId, String memoryId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(memoryId)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        sourceRelationMapper.updateByQuery(
                UserLongTermMemorySourceRelation.builder()
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("memoryId", memoryId.trim())
                        .eq("isDelete", 0)
        );
        return userLongTermMemoryMapper.updateByQuery(
                UserLongTermMemory.builder()
                        .status(STATUS_DELETED)
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("memoryId", memoryId.trim())
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 逻辑删除某个用户的全部长期记忆及来源关系。
     *
     * @param userId 当前用户 id。
     */
    @Override
    public void softDeleteByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        sourceRelationMapper.updateByQuery(
                UserLongTermMemorySourceRelation.builder()
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("isDelete", 0)
        );
        userLongTermMemoryMapper.updateByQuery(
                UserLongTermMemory.builder()
                        .status(STATUS_DELETED)
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("isDelete", 0)
        );
    }

    /**
     * 获取某会话下的活跃记忆 memoryId 列表。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return memoryId 列表。
     */
    @Override
    public List<String> getMemoryIdsByConversation(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return List.of();
        }
        return userLongTermMemoryMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("originConversationId", conversationId.trim())
                .eq("isDelete", 0)
                .eq("status", STATUS_ACTIVE))
                .stream()
                .map(UserLongTermMemory::getMemoryId)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    @Override
    public void deleteByConversationId(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserLongTermMemory> memories = userLongTermMemoryMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("originConversationId", conversationId.trim())
                .eq("isDelete", 0)
                .eq("status", STATUS_ACTIVE));
        if (memories.isEmpty()) {
            return;
        }
        List<String> memoryIds = memories.stream()
                .map(UserLongTermMemory::getMemoryId)
                .filter(StringUtils::isNotBlank)
                .toList();
        if (memoryIds.isEmpty()) {
            return;
        }
        sourceRelationMapper.updateByQuery(
                UserLongTermMemorySourceRelation.builder()
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );
        userLongTermMemoryMapper.updateByQuery(
                UserLongTermMemory.builder()
                        .status(STATUS_DELETED)
                        .isDelete(1)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("originConversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );
    }

    /**
     * 确保某个会话存在长期记忆处理状态记录。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void ensureConversationState(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return;
        }
        UserLongTermMemoryConversationState existing = conversationStateMapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
        if (existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        conversationStateMapper.insert(UserLongTermMemoryConversationState.builder()
                .userId(userId)
                .conversationId(conversationId.trim())
                .status(CONVERSATION_STATUS_UNPROCESSED)
                .pendingCompletedRounds(0)
                .lastBuiltAt(null)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build());
    }

    /**
     * 为某个会话累计待学习轮次，并标记为未处理状态。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param roundCount 本次新增的完整轮次数量。
     * @return 更新后的 pendingCompletedRounds。
     */
    @Override
    public int incrementPendingRounds(Long userId, String conversationId, int roundCount) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId) || roundCount <= 0) {
            return 0;
        }
        ensureConversationState(userId, conversationId);
        UserLongTermMemoryConversationState existing = conversationStateMapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
        if (existing == null) {
            return 0;
        }
        int current = existing.getPendingCompletedRounds() == null ? 0 : existing.getPendingCompletedRounds();
        int updated = current + roundCount;
        existing.setPendingCompletedRounds(updated);
        existing.setStatus(CONVERSATION_STATUS_UNPROCESSED);
        existing.setUpdateTime(LocalDateTime.now());
        conversationStateMapper.update(existing);
        return updated;
    }

    /**
     * 将某个会话标记为待重新处理状态。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void markConversationUnprocessed(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return;
        }
        ensureConversationState(userId, conversationId);
        conversationStateMapper.updateByQuery(
                UserLongTermMemoryConversationState.builder()
                        .status(CONVERSATION_STATUS_UNPROCESSED)
                        .pendingCompletedRounds(0)
                        .updateTime(LocalDateTime.now())
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );
    }

    /**
     * 将当前用户的全部会话状态重置为待处理。
     *
     * @param userId 当前用户 id。
     */
    @Override
    public void markAllUserConversationsUnprocessed(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        conversationStateMapper.updateByQuery(
                UserLongTermMemoryConversationState.builder()
                        .status(CONVERSATION_STATUS_UNPROCESSED)
                        .pendingCompletedRounds(0)
                        .updateTime(LocalDateTime.now())
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("isDelete", 0)
        );
    }

    /**
     * 删除 `delete Conversation State` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    @Override
    public void deleteConversationState(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return;
        }
        conversationStateMapper.updateByQuery(
                UserLongTermMemoryConversationState.builder()
                        .isDelete(1)
                        .updateTime(LocalDateTime.now())
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );
    }

    /**
     * 查询 `list Pending Conversation States` 对应集合。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
    @Override
    public List<UserLongTermMemoryConversationState> listPendingConversationStates(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return conversationStateMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .eq("status", CONVERSATION_STATUS_UNPROCESSED)
                .orderBy("updateTime", true)
                .orderBy("id", true));
    }

    /**
     * 查询 `list User Ids With Pending Conversation States` 对应集合。
     *
     * @return 返回处理结果。
     */
    @Override
    public List<Long> listUserIdsWithPendingConversationStates() {
        List<UserLongTermMemoryConversationState> rows = conversationStateMapper.selectListByQuery(QueryWrapper.create()
                .eq("isDelete", 0)
                .eq("status", CONVERSATION_STATUS_UNPROCESSED)
                .orderBy("id", true));
        Set<Long> userIds = new LinkedHashSet<>();
        for (UserLongTermMemoryConversationState row : rows) {
            if (row != null && row.getUserId() != null && row.getUserId() > 0) {
                userIds.add(row.getUserId());
            }
        }
        return new ArrayList<>(userIds);
    }

    /**
     * 将某个会话标记为已处理，并记录最近构建时间。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void markConversationProcessed(Long userId, String conversationId) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(conversationId)) {
            return;
        }
        ensureConversationState(userId, conversationId);
        LocalDateTime now = LocalDateTime.now();
        conversationStateMapper.updateByQuery(
                UserLongTermMemoryConversationState.builder()
                        .status(CONVERSATION_STATUS_PROCESSED)
                        .pendingCompletedRounds(0)
                        .lastBuiltAt(now)
                        .updateTime(now)
                        .build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );
    }
}
