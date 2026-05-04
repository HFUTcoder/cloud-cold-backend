package com.shenchen.cloudcoldagent.service.usermemory.impl;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryConversationState;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryExtractionItem;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryExtractionResult;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserLongTermMemoryVO;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserPetStateVO;
import com.shenchen.cloudcoldagent.prompts.UserLongTermMemoryPrompts;
import com.shenchen.cloudcoldagent.service.UserConversationRelationService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryStore;
import com.shenchen.cloudcoldagent.workflow.skill.service.StructuredOutputAgentExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UserLongTermMemoryServiceImpl implements UserLongTermMemoryService {

    private static final String USER_MEMORY_ENABLED_KEY = "user_memory:enabled:";
    private static final String USER_MEMORY_PET_NAME_KEY = "user_memory:pet_name:";
    private static final String USER_MEMORY_LAST_LEARNED_AT_KEY = "user_memory:last_learned_at:";
    private static final String LONG_TERM_MEMORY_USER_LOCK_PREFIX = "long_term_memory:user:";

    private final UserLongTermMemoryStore userLongTermMemoryStore;
    private final UserLongTermMemoryMetadataService metadataService;
    private final UserConversationRelationService userConversationRelationService;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    public UserLongTermMemoryServiceImpl(UserLongTermMemoryStore userLongTermMemoryStore,
                                         UserLongTermMemoryMetadataService metadataService,
                                         UserConversationRelationService userConversationRelationService,
                                         ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                         StructuredOutputAgentExecutor structuredOutputAgentExecutor,
                                         StringRedisTemplate stringRedisTemplate,
                                         LongTermMemoryProperties properties,
                                         ObjectMapper objectMapper,
                                         RedissonClient redissonClient) {
        this.userLongTermMemoryStore = userLongTermMemoryStore;
        this.metadataService = metadataService;
        this.userConversationRelationService = userConversationRelationService;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.structuredOutputAgentExecutor = structuredOutputAgentExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient;
    }

    @Override
    public UserPetStateVO getPetState(Long userId) {
        validateUserId(userId);
        UserPetStateVO vo = new UserPetStateVO();
        vo.setEnabled(isEnabled(userId));
        vo.setPetName(resolvePetName(userId));
        vo.setPendingConversationCount(resolvePendingConversationCount(userId));
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
    public boolean setEnabled(Long userId, boolean enabled) {
        validateUserId(userId);
        stringRedisTemplate.opsForValue().set(USER_MEMORY_ENABLED_KEY + userId, String.valueOf(enabled));
        return true;
    }

    @Override
    public boolean renamePet(Long userId, String petName) {
        validateUserId(userId);
        if (StringUtils.isBlank(petName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "petName 不能为空");
        }
        stringRedisTemplate.opsForValue().set(USER_MEMORY_PET_NAME_KEY + userId, petName.trim());
        return true;
    }

    @Override
    public boolean deleteMemory(Long userId, String memoryId) {
        validateUserId(userId);
        if (StringUtils.isBlank(memoryId)) {
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
    public void onAssistantMessagePersisted(Long userId, String conversationId, int assistantMessageCount) {
        if (!properties.isEnabled() || !isEnabled(userId) || StringUtils.isBlank(conversationId) || assistantMessageCount <= 0) {
            return;
        }
        metadataService.incrementPendingRounds(userId, conversationId, assistantMessageCount);
        UserLongTermMemoryConversationState state = metadataService.listPendingConversationStates(userId).stream()
                .filter(item -> conversationId.equals(item.getConversationId()))
                .findFirst()
                .orElse(null);
        if (state == null) {
            return;
        }
        int pendingRounds = state.getPendingCompletedRounds() == null ? 0 : state.getPendingCompletedRounds();
        if (pendingRounds < properties.getTriggerRounds()) {
            return;
        }
        processPendingConversations(userId);
    }

    @Override
    public void onConversationDeleted(Long userId, String conversationId) {
        if (!properties.isEnabled() || !isEnabled(userId) || StringUtils.isBlank(conversationId)) {
            return;
        }
        try {
            metadataService.deleteByConversationId(userId, conversationId);
            userLongTermMemoryStore.deleteByConversationId(userId, conversationId);
        } catch (Exception e) {
            log.warn("删除会话长期记忆失败，userId={}, conversationId={}, message={}",
                    userId, conversationId, e.getMessage(), e);
        } finally {
            metadataService.deleteConversationState(userId, conversationId);
        }
    }

    @Override
    public void onHistoryDeleted(Long userId, String conversationId) {
        if (!properties.isEnabled() || !isEnabled(userId) || StringUtils.isBlank(conversationId)) {
            return;
        }
        try {
            metadataService.deleteByConversationId(userId, conversationId);
            userLongTermMemoryStore.deleteByConversationId(userId, conversationId);
        } catch (Exception e) {
            log.warn("删除聊天记录对应长期记忆失败，userId={}, conversationId={}, message={}",
                    userId, conversationId, e.getMessage(), e);
        }
        metadataService.markConversationUnprocessed(userId, conversationId);
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
            return deduplicateMemories(filteredDocs);
        } catch (Exception e) {
            log.warn("检索长期记忆失败，userId={}, message={}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public void processPendingConversations(Long userId) {
        if (!properties.isEnabled() || !isEnabled(userId) || userId == null || userId <= 0) {
            return;
        }
        String lockName = LONG_TERM_MEMORY_USER_LOCK_PREFIX + userId;
        RLock lock = redissonClient.getLock(lockName);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 5, TimeUnit.MINUTES);
            if (!locked) {
                return;
            }
            doProcessPendingConversations(userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("处理长期记忆待处理会话失败，userId={}, message={}", userId, e.getMessage(), e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doProcessPendingConversations(Long userId) throws Exception {
        List<UserLongTermMemoryConversationState> pendingStates = metadataService.listPendingConversationStates(userId);
        if (pendingStates.isEmpty()) {
            return;
        }
        for (UserLongTermMemoryConversationState state : pendingStates) {
            if (state == null || StringUtils.isBlank(state.getConversationId())) {
                continue;
            }
            rebuildConversationMemories(userId, state.getConversationId());
            metadataService.markConversationProcessed(userId, state.getConversationId());
        }
        markLearned(userId);
    }

    private void rebuildConversationMemories(Long userId, String conversationId) throws Exception {
        metadataService.deleteByConversationId(userId, conversationId);
        userLongTermMemoryStore.deleteByConversationId(userId, conversationId);

        List<ChatMemoryHistory> histories = chatMemoryHistoryMapper.selectListByQuery(QueryWrapper.create()
                .eq("conversationId", conversationId)
                .eq("isDelete", 0)
                .orderBy("createTime", true)
                .orderBy("id", true));
        if (histories == null || histories.isEmpty()) {
            return;
        }

        List<Map<String, Object>> transcript = new ArrayList<>();
        Set<Long> sourceHistoryIds = new LinkedHashSet<>();
        Map<Long, String> conversationByHistoryId = new LinkedHashMap<>();
        for (ChatMemoryHistory history : histories) {
            if (history == null || StringUtils.isBlank(history.getContent())) {
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
        if (transcript.isEmpty()) {
            return;
        }

        List<UserLongTermMemoryDoc> memories = extractConversationMemoriesWithModel(
                userId,
                conversationId,
                transcript,
                sourceHistoryIds,
                conversationByHistoryId
        );
        if (memories.isEmpty()) {
            return;
        }
        userLongTermMemoryStore.addMemories(userId, memories);
        metadataService.upsertMemories(userId, conversationId, memories);
        log.info("长期记忆会话重建完成，userId={}, conversationId={}, historyCount={}, memoryCount={}",
                userId, conversationId, transcript.size(), memories.size());
    }

    private List<UserLongTermMemoryDoc> extractConversationMemoriesWithModel(Long userId,
                                                                             String conversationId,
                                                                             List<Map<String, Object>> transcript,
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
                            conversationId,
                            item,
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
            log.warn("结构化长期记忆提炼失败，userId={}, conversationId={}, message={}",
                    userId, conversationId, e.getMessage(), e);
        }
        return fallbackMemories(userId, conversationId, transcript, sourceHistoryIds, conversationByHistoryId);
    }

    private List<UserLongTermMemoryDoc> fallbackMemories(Long userId,
                                                         String conversationId,
                                                         List<Map<String, Object>> transcript,
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
        LocalDateTime now = LocalDateTime.now();
        UserLongTermMemoryDoc memory = UserLongTermMemoryDoc.builder()
                .id(buildMemoryId(userId))
                .userId(userId)
                .memoryType("PREFERENCE")
                .title("近期高频交互偏好")
                .content(abbreviate(merged, properties.getMaxMemoryChars()))
                .summary("近期交互高频内容")
                .embeddingText("[PREFERENCE] " + abbreviate(merged, properties.getMaxMemoryChars()))
                .confidence(0.45d)
                .importance(0.5d)
                .originConversationId(conversationId)
                .sourceConversationIds(List.of(conversationId))
                .sourceHistoryIds(new ArrayList<>(sourceHistoryIds))
                .sourceConversationsByHistoryId(new LinkedHashMap<>(buildFilteredConversationMap(new ArrayList<>(sourceHistoryIds), conversationByHistoryId)))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return List.of(memory);
    }

    private UserLongTermMemoryDoc buildMemoryDoc(Long userId,
                                                 String conversationId,
                                                 UserLongTermMemoryExtractionItem item,
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
        double confidence = toDouble(item.getConfidence(), 0.5d);
        double importance = toDouble(item.getImportance(), 0.5d);
        LocalDateTime now = LocalDateTime.now();
        return UserLongTermMemoryDoc.builder()
                .id(buildMemoryId(userId))
                .userId(userId)
                .memoryType(memoryType)
                .title(title.isBlank() ? memoryType : abbreviate(title, 40))
                .content(abbreviate(content, properties.getMaxMemoryChars()))
                .summary(abbreviate(summary.isBlank() ? content : summary, 60))
                .embeddingText("[" + memoryType + "] " + abbreviate(content, properties.getMaxMemoryChars()))
                .confidence(confidence)
                .importance(importance)
                .originConversationId(conversationId)
                .sourceConversationIds(List.of(conversationId))
                .sourceHistoryIds(new ArrayList<>(extractedSourceHistoryIds))
                .sourceConversationsByHistoryId(new LinkedHashMap<>(filteredConversationMap))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private List<UserLongTermMemoryDoc> deduplicateMemories(List<UserLongTermMemoryDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        Map<String, UserLongTermMemoryDoc> deduplicated = new LinkedHashMap<>();
        for (UserLongTermMemoryDoc doc : docs) {
            if (doc == null || StringUtils.isBlank(doc.getContent())) {
                continue;
            }
            String key = StringUtils.defaultString(doc.getMemoryType()) + "|" + doc.getContent().trim();
            deduplicated.putIfAbsent(key, doc);
        }
        return new ArrayList<>(deduplicated.values());
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

    private int resolvePendingConversationCount(Long userId) {
        return metadataService.listPendingConversationStates(userId).size();
    }

    private void markLearned(Long userId) {
        stringRedisTemplate.opsForValue().set(USER_MEMORY_LAST_LEARNED_AT_KEY + userId, LocalDateTime.now().toString());
    }

    private LocalDateTime resolveLastLearnedAt(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(USER_MEMORY_LAST_LEARNED_AT_KEY + userId);
        if (StringUtils.isBlank(value)) {
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
        if (resolvePendingConversationCount(userId) > 0) {
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

    private String buildMemoryId(Long userId) {
        return userId + "_" + IdUtil.getSnowflakeNextIdStr();
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
