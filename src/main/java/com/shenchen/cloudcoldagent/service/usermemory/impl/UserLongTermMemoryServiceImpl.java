package com.shenchen.cloudcoldagent.service.usermemory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryExtractionItem;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryExtractionResult;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserLongTermMemoryVO;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserPetStateVO;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.prompts.UserLongTermMemoryPrompts;
import com.shenchen.cloudcoldagent.service.UserConversationRelationService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.shenchen.cloudcoldagent.workflow.skill.service.StructuredOutputAgentExecutor;

@Service
@Slf4j
public class UserLongTermMemoryServiceImpl implements UserLongTermMemoryService {

    private static final String USER_MEMORY_ENABLED_KEY = "user_memory:enabled:";
    private static final String USER_MEMORY_PET_NAME_KEY = "user_memory:pet_name:";
    private static final String USER_MEMORY_PENDING_ROUNDS_KEY = "user_memory:pending_rounds:";
    private static final String USER_MEMORY_BUILDING_KEY = "user_memory:building:";
    private static final String USER_MEMORY_LAST_LEARNED_AT_KEY = "user_memory:last_learned_at:";

    private final UserLongTermMemoryStore userLongTermMemoryStore;
    private final UserLongTermMemoryMetadataService metadataService;
    private final UserConversationRelationService userConversationRelationService;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public UserLongTermMemoryServiceImpl(UserLongTermMemoryStore userLongTermMemoryStore,
                                         UserLongTermMemoryMetadataService metadataService,
                                         UserConversationRelationService userConversationRelationService,
                                         ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                         StructuredOutputAgentExecutor structuredOutputAgentExecutor,
                                         StringRedisTemplate stringRedisTemplate,
                                         LongTermMemoryProperties properties,
                                         ObjectMapper objectMapper) {
        this.userLongTermMemoryStore = userLongTermMemoryStore;
        this.metadataService = metadataService;
        this.userConversationRelationService = userConversationRelationService;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.structuredOutputAgentExecutor = structuredOutputAgentExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public UserPetStateVO getPetState(Long userId) {
        validateUserId(userId);
        UserPetStateVO vo = new UserPetStateVO();
        vo.setEnabled(isEnabled(userId));
        vo.setPetName(resolvePetName(userId));
        vo.setPendingRounds(resolvePendingRounds(userId));
        vo.setPetMood(resolvePetMood(userId));
        vo.setLastLearnedAt(resolveLastLearnedAt(userId));

        try {
            List<UserLongTermMemory> memories = metadataService.listActiveByUserId(userId, 100);
            Map<String, List<UserLongTermMemorySourceRelation>> sourceMap = metadataService.mapSourcesByMemoryIds(
                    memories.stream().map(UserLongTermMemory::getMemoryId).toList()
            );
            vo.setMemoryCount(memories.size());
            vo.setRecentMemories(memories.stream().limit(3).map(memory -> toVO(memory, sourceMap.get(memory.getMemoryId()))).toList());
            vo.setMemoryHighlights(memories.stream()
                    .map(UserLongTermMemory::getSummary)
                    .filter(StringUtils::isNotBlank)
                    .limit(3)
                    .toList());
        } catch (Exception e) {
            log.warn("读取宠物状态失败，userId={}, message={}", userId, e.getMessage(), e);
            vo.setMemoryCount(0);
            vo.setRecentMemories(List.of());
            vo.setMemoryHighlights(List.of());
        }
        return vo;
    }

    @Override
    public List<UserLongTermMemoryVO> listMemories(Long userId) {
        validateUserId(userId);
        try {
            List<UserLongTermMemory> memories = metadataService.listActiveByUserId(userId, 100);
            Map<String, List<UserLongTermMemorySourceRelation>> sourceMap = metadataService.mapSourcesByMemoryIds(
                    memories.stream().map(UserLongTermMemory::getMemoryId).toList()
            );
            return memories.stream()
                    .map(memory -> toVO(memory, sourceMap.get(memory.getMemoryId())))
                    .toList();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取长期记忆失败");
        }
    }

    @Override
    public boolean triggerRebuild(Long userId) {
        validateUserId(userId);
        if (!isEnabled(userId)) {
            return false;
        }
        scheduleRebuild(userId, true);
        return true;
    }

    @Override
    public boolean setEnabled(Long userId, boolean enabled) {
        validateUserId(userId);
        stringRedisTemplate.opsForValue().set(USER_MEMORY_ENABLED_KEY + userId, String.valueOf(enabled));
        return true;
    }

    @Override
    public boolean renamePet(Long userId, String petName) {
        validateUserId(userId);
        if (petName == null || petName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "petName 不能为空");
        }
        stringRedisTemplate.opsForValue().set(USER_MEMORY_PET_NAME_KEY + userId, petName.trim());
        return true;
    }

    @Override
    public boolean deleteMemory(Long userId, String memoryId) {
        validateUserId(userId);
        if (memoryId == null || memoryId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "memoryId 不能为空");
        }
        try {
            boolean deleted = metadataService.deleteMemory(userId, memoryId.trim());
            if (deleted) {
                userLongTermMemoryStore.deleteById(userId, memoryId.trim());
            }
            return deleted;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除长期记忆失败");
        }
    }

    @Override
    public void onConversationRoundCompleted(Long userId, String conversationId) {
        if (!properties.isEnabled() || !isEnabled(userId)) {
            return;
        }
        Long rounds = stringRedisTemplate.opsForValue().increment(USER_MEMORY_PENDING_ROUNDS_KEY + userId);
        if (rounds != null && rounds >= properties.getTriggerRounds()) {
            scheduleRebuild(userId, false);
        }
    }

    @Override
    public void onConversationDeleted(Long userId, String conversationId) {
        if (!properties.isEnabled() || !isEnabled(userId)) {
            return;
        }
        scheduleRebuild(userId, true);
    }

    @Override
    public void onHistoryDeleted(Long userId, Long historyId) {
        if (!properties.isEnabled() || !isEnabled(userId)) {
            return;
        }
        scheduleRebuild(userId, true);
    }

    @Override
    public List<UserLongTermMemoryDoc> retrieveRelevantMemories(Long userId, String question, int topK) {
        if (!properties.isEnabled() || !isEnabled(userId)) {
            return List.of();
        }
        try {
            List<UserLongTermMemoryDoc> docs = userLongTermMemoryStore.similaritySearch(userId, question, topK);
            List<String> candidateIds = docs.stream()
                    .map(UserLongTermMemoryDoc::getId)
                    .filter(StringUtils::isNotBlank)
                    .toList();
            Map<String, UserLongTermMemory> activeMemoryMap = metadataService.mapActiveByMemoryIds(userId, candidateIds);
            List<UserLongTermMemoryDoc> filteredDocs = docs.stream()
                    .filter(doc -> doc != null && activeMemoryMap.containsKey(doc.getId()))
                    .toList();
            metadataService.markRetrieved(userId, filteredDocs.stream().map(UserLongTermMemoryDoc::getId).toList());
            return filteredDocs;
        } catch (Exception e) {
            log.warn("检索长期记忆失败，userId={}, message={}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    private void scheduleRebuild(Long userId, boolean force) {
        String lockKey = USER_MEMORY_BUILDING_KEY + userId;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                rebuildUserMemories(userId, force);
            } catch (Exception e) {
                log.warn("重建长期记忆失败，userId={}, message={}", userId, e.getMessage(), e);
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        }, executor);
    }

    private void rebuildUserMemories(Long userId, boolean force) throws Exception {
        List<String> conversationIds = userConversationRelationService.listConversationIdsByUserId(userId);
        if (conversationIds == null || conversationIds.isEmpty()) {
            userLongTermMemoryStore.deleteByUserId(userId);
            metadataService.softDeleteByUserId(userId);
            resetPendingRounds(userId);
            markLearned(userId);
            return;
        }

        List<Map<String, Object>> transcript = new ArrayList<>();
        Set<String> sourceConversationIds = new LinkedHashSet<>();
        Set<Long> sourceHistoryIds = new LinkedHashSet<>();
        Map<Long, String> conversationByHistoryId = new LinkedHashMap<>();
        for (String conversationId : conversationIds) {
            if (!Boolean.TRUE.equals(userConversationRelationService.isConversationOwnedByUser(userId, conversationId))) {
                continue;
            }
            List<ChatMemoryHistory> histories = chatMemoryHistoryMapper.selectListByQuery(QueryWrapper.create()
                    .eq("conversationId", conversationId)
                    .eq("isDelete", 0)
                    .orderBy("createTime", true)
                    .orderBy("id", true));
            if (histories == null || histories.isEmpty()) {
                continue;
            }
            sourceConversationIds.add(conversationId);
            for (ChatMemoryHistory history : histories) {
                if (history == null || history.getContent() == null || history.getContent().isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("historyId", history.getId());
                row.put("conversationId", history.getConversationId());
                row.put("messageType", history.getMessageType());
                row.put("content", abbreviate(history.getContent(), 1200));
                transcript.add(row);
                if (history.getId() != null) {
                    sourceHistoryIds.add(history.getId());
                    conversationByHistoryId.put(history.getId(), conversationId);
                }
            }
        }

        if (transcript.isEmpty()) {
            userLongTermMemoryStore.deleteByUserId(userId);
            metadataService.softDeleteByUserId(userId);
            resetPendingRounds(userId);
            markLearned(userId);
            return;
        }

        List<UserLongTermMemoryDoc> memories = extractMemoriesWithModel(
                userId,
                transcript,
                sourceConversationIds,
                sourceHistoryIds,
                conversationByHistoryId
        );
        userLongTermMemoryStore.replaceAll(userId, memories);
        metadataService.replaceAll(userId, memories);
        resetPendingRounds(userId);
        markLearned(userId);
        log.info("长期记忆重建完成，userId={}, conversationCount={}, historyCount={}, memoryCount={}, force={}",
                userId,
                sourceConversationIds.size(),
                transcript.size(),
                memories.size(),
                force);
    }

    private List<UserLongTermMemoryDoc> extractMemoriesWithModel(Long userId,
                                                                 List<Map<String, Object>> transcript,
                                                                 Set<String> sourceConversationIds,
                                                                 Set<Long> sourceHistoryIds,
                                                                 Map<Long, String> conversationByHistoryId) {
        String transcriptJson = UserLongTermMemoryPrompts.renderTranscriptJson(objectMapper, transcript);
        String systemPrompt = UserLongTermMemoryPrompts.buildExtractionSystemPrompt();
        String userPrompt = UserLongTermMemoryPrompts.buildExtractionUserPrompt(userId, transcriptJson);
        try {
            UserLongTermMemoryExtractionResult result = structuredOutputAgentExecutor.execute(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ), UserLongTermMemoryExtractionResult.class);
            List<UserLongTermMemoryExtractionItem> items = result == null ? List.of() : result.getItems();
            List<UserLongTermMemoryDoc> memories = new ArrayList<>();
            if (items != null) {
                for (UserLongTermMemoryExtractionItem item : items) {
                    UserLongTermMemoryDoc memory = buildMemoryDoc(
                            userId,
                            item,
                            sourceConversationIds,
                            sourceHistoryIds,
                            conversationByHistoryId
                    );
                    if (memory != null) {
                        memories.add(memory);
                    }
                }
            }
            if (!memories.isEmpty()) {
                return memories;
            }
        } catch (Exception e) {
            log.warn("结构化长期记忆提炼失败，userId={}, message={}", userId, e.getMessage(), e);
        }
        return fallbackMemories(userId, transcript, sourceConversationIds, sourceHistoryIds, conversationByHistoryId);
    }

    private List<UserLongTermMemoryDoc> fallbackMemories(Long userId,
                                                         List<Map<String, Object>> transcript,
                                                         Set<String> sourceConversationIds,
                                                         Set<Long> sourceHistoryIds,
                                                         Map<Long, String> conversationByHistoryId) {
        List<String> userMessages = transcript.stream()
                .filter(item -> "USER".equalsIgnoreCase(Objects.toString(item.get("messageType"), "")))
                .map(item -> Objects.toString(item.get("content"), ""))
                .filter(StringUtils::isNotBlank)
                .limit(6)
                .toList();
        if (userMessages.isEmpty()) {
            return List.of();
        }
        String merged = String.join("；", userMessages);
        UserLongTermMemoryDoc memory = UserLongTermMemoryDoc.builder()
                .id(buildStableMemoryId(userId, "PREFERENCE", "近期高频交互偏好", merged))
                .userId(userId)
                .memoryType("PREFERENCE")
                .title("近期高频交互偏好")
                .content(abbreviate(merged, properties.getMaxMemoryChars()))
                .summary("近期交互高频内容")
                .embeddingText("[PREFERENCE] " + abbreviate(merged, properties.getMaxMemoryChars()))
                .confidence(0.45d)
                .importance(0.5d)
                .sourceConversationIds(new ArrayList<>(sourceConversationIds))
                .sourceHistoryIds(new ArrayList<>(sourceHistoryIds))
                .sourceConversationsByHistoryId(
                        new LinkedHashMap<>(buildFilteredConversationMap(new ArrayList<>(sourceHistoryIds), conversationByHistoryId))
                )
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return List.of(memory);
    }

    private UserLongTermMemoryDoc buildMemoryDoc(Long userId,
                                                 UserLongTermMemoryExtractionItem item,
                                                 Set<String> sourceConversationIds,
                                                 Set<Long> sourceHistoryIds,
                                                 Map<Long, String> conversationByHistoryId) {
        if (item == null) {
            return null;
        }
        String memoryType = Objects.toString(item.getMemoryType(), "").trim();
        String title = Objects.toString(item.getTitle(), "").trim();
        String content = Objects.toString(item.getContent(), "").trim();
        String summary = Objects.toString(item.getSummary(), "").trim();
        if (memoryType.isBlank() || content.isBlank()) {
            return null;
        }
        List<Long> extractedSourceHistoryIds = normalizeSourceHistoryIds(item.getSourceHistoryIds(), sourceHistoryIds);
        if (extractedSourceHistoryIds.isEmpty()) {
            return null;
        }
        Map<Long, String> filteredConversationMap = buildFilteredConversationMap(extractedSourceHistoryIds, conversationByHistoryId);
        List<String> filteredConversationIds = filteredConversationMap.values().stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        double confidence = toDouble(item.getConfidence(), 0.5d);
        double importance = toDouble(item.getImportance(), 0.5d);
        LocalDateTime now = LocalDateTime.now();
        return UserLongTermMemoryDoc.builder()
                .id(buildStableMemoryId(userId, memoryType, title, content))
                .userId(userId)
                .memoryType(memoryType)
                .title(title.isBlank() ? memoryType : abbreviate(title, 40))
                .content(abbreviate(content, properties.getMaxMemoryChars()))
                .summary(abbreviate(summary.isBlank() ? content : summary, 60))
                .embeddingText("[" + memoryType + "] " + abbreviate(content, properties.getMaxMemoryChars()))
                .confidence(confidence)
                .importance(importance)
                .sourceConversationIds(new ArrayList<>(filteredConversationIds))
                .sourceHistoryIds(new ArrayList<>(extractedSourceHistoryIds))
                .sourceConversationsByHistoryId(new LinkedHashMap<>(filteredConversationMap))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserLongTermMemoryVO toVO(UserLongTermMemory memory,
                                      List<UserLongTermMemorySourceRelation> sources) {
        UserLongTermMemoryVO vo = new UserLongTermMemoryVO();
        vo.setId(memory.getMemoryId());
        vo.setStatus(memory.getStatus());
        vo.setMemoryType(memory.getMemoryType());
        vo.setTitle(memory.getTitle());
        vo.setContent(memory.getContent());
        vo.setSummary(memory.getSummary());
        vo.setConfidence(memory.getConfidence());
        vo.setImportance(memory.getImportance());
        vo.setSourceConversationIds(resolveSourceConversationIds(sources));
        vo.setSourceHistoryCount(sources == null ? 0 : sources.size());
        vo.setLastRetrievedAt(memory.getLastRetrievedAt());
        vo.setLastReinforcedAt(memory.getLastReinforcedAt());
        vo.setUpdatedAt(memory.getUpdateTime());
        return vo;
    }

    private List<String> resolveSourceConversationIds(List<UserLongTermMemorySourceRelation> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .map(UserLongTermMemorySourceRelation::getConversationId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private String resolvePetName(Long userId) {
        String petName = stringRedisTemplate.opsForValue().get(USER_MEMORY_PET_NAME_KEY + userId);
        return StringUtils.isBlank(petName) ? properties.getDefaultPetName() : petName;
    }

    private boolean isEnabled(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        String value = stringRedisTemplate.opsForValue().get(USER_MEMORY_ENABLED_KEY + userId);
        if (value == null) {
            return properties.isEnabled();
        }
        return Boolean.parseBoolean(value);
    }

    private int resolvePendingRounds(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(USER_MEMORY_PENDING_ROUNDS_KEY + userId);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void resetPendingRounds(Long userId) {
        stringRedisTemplate.opsForValue().set(USER_MEMORY_PENDING_ROUNDS_KEY + userId, "0");
    }

    private void markLearned(Long userId) {
        stringRedisTemplate.opsForValue().set(USER_MEMORY_LAST_LEARNED_AT_KEY + userId, LocalDateTime.now().toString());
    }

    private LocalDateTime resolveLastLearnedAt(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(USER_MEMORY_LAST_LEARNED_AT_KEY + userId);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolvePetMood(Long userId) {
        if (!isEnabled(userId)) {
            return "disabled";
        }
        int pendingRounds = resolvePendingRounds(userId);
        if (pendingRounds >= Math.max(1, properties.getTriggerRounds() - 1)) {
            return "learning";
        }
        LocalDateTime lastLearnedAt = resolveLastLearnedAt(userId);
        if (lastLearnedAt != null && lastLearnedAt.isAfter(LocalDateTime.now().minusMinutes(10))) {
            return "updated";
        }
        return "idle";
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    private List<Long> normalizeSourceHistoryIds(List<Long> sourceHistoryIds, Set<Long> allowedHistoryIds) {
        if (sourceHistoryIds == null || sourceHistoryIds.isEmpty() || allowedHistoryIds == null || allowedHistoryIds.isEmpty()) {
            return List.of();
        }
        return sourceHistoryIds.stream()
                .filter(Objects::nonNull)
                .filter(historyId -> historyId > 0 && allowedHistoryIds.contains(historyId))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private Map<Long, String> buildFilteredConversationMap(List<Long> historyIds,
                                                           Map<Long, String> conversationByHistoryId) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (historyIds == null || historyIds.isEmpty() || conversationByHistoryId == null || conversationByHistoryId.isEmpty()) {
            return result;
        }
        for (Long historyId : historyIds) {
            if (historyId == null || historyId <= 0) {
                continue;
            }
            String conversationId = conversationByHistoryId.get(historyId);
            if (StringUtils.isNotBlank(conversationId)) {
                result.put(historyId, conversationId);
            }
        }
        return result;
    }

    private String buildStableMemoryId(Long userId,
                                       String memoryType,
                                       String title,
                                       String content) {
        String normalizedType = Objects.toString(memoryType, "").trim().toUpperCase();
        String normalizedTitle = abbreviate(Objects.toString(title, ""), 80);
        String normalizedContent = abbreviate(Objects.toString(content, ""), 220);
        String signature = userId + "|" + normalizedType + "|" + normalizedTitle + "|" + normalizedContent;
        return java.util.UUID.nameUUIDFromBytes(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", "\n").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
