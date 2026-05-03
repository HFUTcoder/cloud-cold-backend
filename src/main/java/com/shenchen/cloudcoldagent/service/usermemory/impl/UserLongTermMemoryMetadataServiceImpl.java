package com.shenchen.cloudcoldagent.service.usermemory.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.mapper.UserLongTermMemoryMapper;
import com.shenchen.cloudcoldagent.mapper.UserLongTermMemorySourceRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class UserLongTermMemoryMetadataServiceImpl implements UserLongTermMemoryMetadataService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";

    private final UserLongTermMemoryMapper userLongTermMemoryMapper;
    private final UserLongTermMemorySourceRelationMapper sourceRelationMapper;

    public UserLongTermMemoryMetadataServiceImpl(UserLongTermMemoryMapper userLongTermMemoryMapper,
                                                 UserLongTermMemorySourceRelationMapper sourceRelationMapper) {
        this.userLongTermMemoryMapper = userLongTermMemoryMapper;
        this.sourceRelationMapper = sourceRelationMapper;
    }

    @Override
    public void replaceAll(Long userId, List<UserLongTermMemoryDoc> memories) {
        if (userId == null || userId <= 0) {
            return;
        }
        softDeleteByUserId(userId);
        if (memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (UserLongTermMemoryDoc memory : mergeDuplicateMemories(memories)) {
            if (memory == null || memory.getId() == null || memory.getId().isBlank()) {
                continue;
            }
            UserLongTermMemory existing = userLongTermMemoryMapper.selectOneByQuery(QueryWrapper.create()
                    .eq("memoryId", memory.getId()));
            if (existing != null) {
                existing.setUserId(userId);
                existing.setMemoryType(memory.getMemoryType());
                existing.setTitle(memory.getTitle());
                existing.setContent(memory.getContent());
                existing.setSummary(memory.getSummary());
                existing.setConfidence(memory.getConfidence());
                existing.setImportance(memory.getImportance());
                existing.setStatus(STATUS_ACTIVE);
                existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
                existing.setLastReinforcedAt(now);
                existing.setUpdateTime(memory.getUpdatedAt() == null ? now : memory.getUpdatedAt());
                existing.setIsDelete(0);
                userLongTermMemoryMapper.update(existing);
            } else {
                userLongTermMemoryMapper.insert(UserLongTermMemory.builder()
                        .memoryId(memory.getId())
                        .userId(userId)
                        .memoryType(memory.getMemoryType())
                        .title(memory.getTitle())
                        .content(memory.getContent())
                        .summary(memory.getSummary())
                        .confidence(memory.getConfidence())
                        .importance(memory.getImportance())
                        .status(STATUS_ACTIVE)
                        .version(1)
                        .lastRetrievedAt(null)
                        .lastReinforcedAt(now)
                        .createTime(memory.getCreatedAt() == null ? now : memory.getCreatedAt())
                        .updateTime(memory.getUpdatedAt() == null ? now : memory.getUpdatedAt())
                        .isDelete(0)
                        .build());
            }

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

    @Override
    public boolean deleteMemory(Long userId, String memoryId) {
        if (userId == null || userId <= 0 || memoryId == null || memoryId.isBlank()) {
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

    private List<UserLongTermMemoryDoc> mergeDuplicateMemories(List<UserLongTermMemoryDoc> memories) {
        Map<String, UserLongTermMemoryDoc> merged = new LinkedHashMap<>();
        for (UserLongTermMemoryDoc memory : memories) {
            if (memory == null || memory.getId() == null || memory.getId().isBlank()) {
                continue;
            }
            UserLongTermMemoryDoc existing = merged.get(memory.getId());
            if (existing == null) {
                merged.put(memory.getId(), copyMemory(memory));
                continue;
            }
            mergeInto(existing, memory);
        }
        return new ArrayList<>(merged.values());
    }

    private UserLongTermMemoryDoc copyMemory(UserLongTermMemoryDoc memory) {
        return UserLongTermMemoryDoc.builder()
                .id(memory.getId())
                .userId(memory.getUserId())
                .memoryType(memory.getMemoryType())
                .title(memory.getTitle())
                .content(memory.getContent())
                .summary(memory.getSummary())
                .embeddingText(memory.getEmbeddingText())
                .confidence(memory.getConfidence())
                .importance(memory.getImportance())
                .sourceConversationIds(memory.getSourceConversationIds() == null ? List.of() : new ArrayList<>(memory.getSourceConversationIds()))
                .sourceHistoryIds(memory.getSourceHistoryIds() == null ? List.of() : new ArrayList<>(memory.getSourceHistoryIds()))
                .sourceConversationsByHistoryId(memory.getSourceConversationsByHistoryId() == null ? Map.of() : new LinkedHashMap<>(memory.getSourceConversationsByHistoryId()))
                .createdAt(memory.getCreatedAt())
                .updatedAt(memory.getUpdatedAt())
                .build();
    }

    private void mergeInto(UserLongTermMemoryDoc target, UserLongTermMemoryDoc incoming) {
        target.setConfidence(max(target.getConfidence(), incoming.getConfidence()));
        target.setImportance(max(target.getImportance(), incoming.getImportance()));
        target.setUpdatedAt(max(target.getUpdatedAt(), incoming.getUpdatedAt()));

        Set<String> conversationIds = new LinkedHashSet<>();
        if (target.getSourceConversationIds() != null) {
            conversationIds.addAll(target.getSourceConversationIds());
        }
        if (incoming.getSourceConversationIds() != null) {
            conversationIds.addAll(incoming.getSourceConversationIds());
        }
        target.setSourceConversationIds(new ArrayList<>(conversationIds));

        Set<Long> historyIds = new LinkedHashSet<>();
        if (target.getSourceHistoryIds() != null) {
            historyIds.addAll(target.getSourceHistoryIds());
        }
        if (incoming.getSourceHistoryIds() != null) {
            historyIds.addAll(incoming.getSourceHistoryIds());
        }
        target.setSourceHistoryIds(new ArrayList<>(historyIds));

        Map<Long, String> historyConversationMap = target.getSourceConversationsByHistoryId() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(target.getSourceConversationsByHistoryId());
        if (incoming.getSourceConversationsByHistoryId() != null) {
            historyConversationMap.putAll(incoming.getSourceConversationsByHistoryId());
        }
        target.setSourceConversationsByHistoryId(historyConversationMap);
    }

    private Double max(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

}
