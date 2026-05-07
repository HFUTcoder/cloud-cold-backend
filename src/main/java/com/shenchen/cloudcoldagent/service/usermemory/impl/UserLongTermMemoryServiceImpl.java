package com.shenchen.cloudcoldagent.service.usermemory.impl;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.limiter.RateLimiter;
import com.shenchen.cloudcoldagent.annotation.DistributeLock;
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
import org.springframework.ai.chat.messages.MessageType;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 长期记忆服务实现，负责宠物状态、记忆列表、记忆重建、召回和删除联动。
 */
@Service
@Slf4j
public class UserLongTermMemoryServiceImpl implements UserLongTermMemoryService {

    private static final String USER_MEMORY_PET_NAME_KEY = "user_memory:pet_name:";
    private static final String USER_MEMORY_LAST_LEARNED_AT_KEY = "user_memory:last_learned_at:";
    private static final Set<String> SUPPORTED_MEMORY_TYPES = Set.of("USER_PROFILE", "FACT", "PREFERENCE");

    private final UserLongTermMemoryStore userLongTermMemoryStore;
    private final UserLongTermMemoryMetadataService metadataService;
    private final UserConversationRelationService userConversationRelationService;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final Executor longTermMemoryExecutor;

    /**
     * 注入长期记忆主链路所需的依赖。
     *
     * @param userLongTermMemoryStore 长期记忆存储服务。
     * @param metadataService 长期记忆元数据服务。
     * @param userConversationRelationService 用户会话归属服务。
     * @param chatMemoryHistoryMapper 历史消息 mapper。
     * @param structuredOutputAgentExecutor 结构化输出执行器。
     * @param stringRedisTemplate Redis 模板。
     * @param properties 长期记忆配置。
     * @param objectMapper 对象映射器。
     * @param rateLimiter 限流器。
     * @param longTermMemoryExecutor 长期记忆异步处理线程池。
     */
    public UserLongTermMemoryServiceImpl(UserLongTermMemoryStore userLongTermMemoryStore,
                                         UserLongTermMemoryMetadataService metadataService,
                                         UserConversationRelationService userConversationRelationService,
                                         ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                         StructuredOutputAgentExecutor structuredOutputAgentExecutor,
                                         StringRedisTemplate stringRedisTemplate,
                                         LongTermMemoryProperties properties,
                                         ObjectMapper objectMapper,
                                         RateLimiter rateLimiter,
                                         Executor longTermMemoryExecutor) {
        this.userLongTermMemoryStore = userLongTermMemoryStore;
        this.metadataService = metadataService;
        this.userConversationRelationService = userConversationRelationService;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.structuredOutputAgentExecutor = structuredOutputAgentExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.longTermMemoryExecutor = longTermMemoryExecutor;
    }

    /**
     * 生成当前用户的宠物记忆状态摘要。
     *
     * @param userId 当前用户 id。
     * @return 宠物状态 VO。
     */
    @Override
    public UserPetStateVO getPetState(Long userId) {
        validateUserId(userId);
        UserPetStateVO vo = new UserPetStateVO();
        vo.setEnabled(true);
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

    /**
     * 列出当前用户的长期记忆条目。
     *
     * @param userId 当前用户 id。
     * @return 长期记忆 VO 列表。
     */
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

    /**
     * 手动触发当前用户所有待学习会话的长期记忆重建。
     *
     * @param userId 当前用户 id。
     * @return 是否触发成功。
     */
    @Override
    public boolean triggerManualRebuild(Long userId) {
        validateUserId(userId);
        if (!properties.isEnabled()) {
            return false;
        }
        boolean allowed = Boolean.TRUE.equals(rateLimiter.tryAcquire("long_term_memory:manual_rebuild:" + userId, 1, 300));
        if (!allowed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "5 分钟内最多主动重建一次长期记忆");
        }
        List<String> conversationIds = userConversationRelationService.listConversationIdsByUserId(userId);
        for (String conversationId : conversationIds) {
            if (StringUtils.isBlank(conversationId)) {
                continue;
            }
            metadataService.ensureConversationState(userId, conversationId);
        }
        metadataService.markAllUserConversationsUnprocessed(userId);
        dispatchPendingConversationsProcessing(userId);
        return true;
    }

    /**
     * 修改当前用户宠物记忆的显示名称。
     *
     * @param userId 当前用户 id。
     * @param petName 新宠物名。
     * @return 是否修改成功。
     */
    @Override
    public boolean renamePet(Long userId, String petName) {
        validateUserId(userId);
        if (StringUtils.isBlank(petName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "petName 不能为空");
        }
        stringRedisTemplate.opsForValue().set(USER_MEMORY_PET_NAME_KEY + userId, petName.trim(), 30, java.util.concurrent.TimeUnit.DAYS);
        return true;
    }

    /**
     * 删除一条长期记忆，并同步清理向量 / 关键词索引中的对应记录。
     *
     * @param userId 当前用户 id。
     * @param memoryId 记忆 id。
     * @return 是否删除成功。
     */
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

    /**
     * 在助手消息落库后更新待学习轮次，必要时异步触发记忆整理。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param assistantMessageCount 本次新增的助手消息数。
     */
    @Override
    public void onAssistantMessagePersisted(Long userId, String conversationId, int assistantMessageCount) {
        if (!properties.isEnabled() || StringUtils.isBlank(conversationId) || assistantMessageCount <= 0) {
            return;
        }
        int pendingRounds = metadataService.incrementPendingRounds(userId, conversationId, assistantMessageCount);
        if (pendingRounds >= properties.getTriggerRounds()) {
            dispatchPendingConversationsProcessing(userId);
        }
    }

    /**
     * 在会话删除后同步清理与该会话关联的长期记忆。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void onConversationDeleted(Long userId, String conversationId) {
        cleanConversationMemories(userId, conversationId, false);
    }

    /**
     * 在聊天历史被删除后清理相关长期记忆，并将该会话标记为待重新处理。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void onHistoryDeleted(Long userId, String conversationId) {
        cleanConversationMemories(userId, conversationId, true);
    }

    /**
     * 统一清理会话关联的长期记忆（MySQL + ES），并按参数决定后续状态。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param markUnprocessed true 标记为待重新处理，false 删除会话状态记录。
     */
    private void cleanConversationMemories(Long userId, String conversationId, boolean markUnprocessed) {
        if (!properties.isEnabled() || StringUtils.isBlank(conversationId)) {
            return;
        }
        try {
            List<String> memoryIds = metadataService.getMemoryIdsByConversation(userId, conversationId);
            metadataService.deleteByConversationId(userId, conversationId);
            if (!memoryIds.isEmpty()) {
                userLongTermMemoryStore.deleteByIds(userId, memoryIds);
            }
        } catch (Exception e) {
            log.warn("清理会话长期记忆失败，userId={}, conversationId={}, message={}",
                    userId, conversationId, e.getMessage(), e);
        }
        if (markUnprocessed) {
            metadataService.markConversationUnprocessed(userId, conversationId);
        } else {
            metadataService.deleteConversationState(userId, conversationId);
        }
    }

    /**
     * 按问题语义召回最相关的长期记忆文档。
     *
     * @param userId 当前用户 id。
     * @param question 当前问题。
     * @param topK 召回数量上限。
     * @return 命中的长期记忆文档。
     */
    @Override
    public List<UserLongTermMemoryDoc> retrieveRelevantMemories(Long userId, String question, int topK) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            List<UserLongTermMemoryDoc> docs = userLongTermMemoryStore.similaritySearch(userId, question, topK);
            List<UserLongTermMemoryDoc> validDocs = docs.stream()
                    .filter(Objects::nonNull)
                    .toList();
            metadataService.markRetrieved(userId, validDocs.stream().map(UserLongTermMemoryDoc::getId).toList());
            return validDocs;
        } catch (Exception e) {
            log.warn("检索长期记忆失败，userId={}, message={}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 处理当前用户所有待整理的会话，并重建长期记忆。
     *
     * @param userId 当前用户 id。
     */
    @Override
    @DistributeLock(scene = "long_term_memory:user", keyExpression = "#userId")
    public void processPendingConversations(Long userId) {
        if (!properties.isEnabled() || userId == null || userId <= 0) {
            return;
        }
        try {
            doProcessPendingConversations(userId);
        } catch (Exception e) {
            log.warn("处理长期记忆待处理会话失败，userId={}, message={}", userId, e.getMessage(), e);
        }
    }

    /**
     * 异步分发一次长期记忆待处理会话扫描任务。
     *
     * @param userId 当前用户 id。
     */
    private void dispatchPendingConversationsProcessing(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        CompletableFuture.runAsync(() -> processPendingConversations(userId), longTermMemoryExecutor);
    }

    /**
     * 实际执行待处理会话扫描与重建逻辑。
     *
     * @param userId 当前用户 id。
     * @throws Exception 重建过程中发生异常时抛出。
     */
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

    /**
     * 按整段会话历史重新提炼并写入该会话对应的长期记忆。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @throws Exception 提炼或写入长期记忆失败时抛出。
     */
    private void rebuildConversationMemories(Long userId, String conversationId) throws Exception {
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
        // 提取成功后再删除旧数据并写入新记忆，避免 LLM 失败导致旧数据已丢失
        List<String> oldMemoryIds = metadataService.getMemoryIdsByConversation(userId, conversationId);
        metadataService.deleteByConversationId(userId, conversationId);
        if (!oldMemoryIds.isEmpty()) {
            userLongTermMemoryStore.deleteByIds(userId, oldMemoryIds);
        }
        userLongTermMemoryStore.addMemories(userId, memories);
        metadataService.upsertMemories(userId, conversationId, memories);
        log.info("长期记忆会话重建完成，userId={}, conversationId={}, historyCount={}, memoryCount={}",
                userId, conversationId, transcript.size(), memories.size());
    }

    /**
     * 调用结构化输出模型，从整段会话历史中提炼长期记忆候选项。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param transcript 会话转录数据。
     * @param sourceHistoryIds 可引用的历史消息 id 集合。
     * @param conversationByHistoryId historyId 到 conversationId 的映射。
     * @return 提炼出的长期记忆文档列表；模型失败时回退到启发式结果。
     */
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

    /**
     * 当结构化提炼失败时，退化生成一条"近期偏好"类型的长期记忆。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param transcript 会话转录数据。
     * @param sourceHistoryIds 可引用的历史消息 id 集合。
     * @param conversationByHistoryId historyId 到 conversationId 的映射。
     * @return 退化生成的长期记忆列表。
     */
    private List<UserLongTermMemoryDoc> fallbackMemories(Long userId,
                                                         String conversationId,
                                                         List<Map<String, Object>> transcript,
                                                         Set<Long> sourceHistoryIds,
                                                         Map<Long, String> conversationByHistoryId) {
        List<String> userMessages = transcript.stream()
                .filter(item -> MessageType.USER.name().equalsIgnoreCase(Objects.toString(item.get("messageType"), "")))
                .map(item -> Objects.toString(item.get("content"), ""))
                .filter(StringUtils::isNotBlank)
                .limit(6)
                .toList();
        if (userMessages.isEmpty()) {
            return List.of();
        }
        String merged = String.join("；", userMessages);
        UserLongTermMemoryExtractionItem item = new UserLongTermMemoryExtractionItem();
        item.setMemoryType("PREFERENCE");
        item.setTitle("近期高频交互偏好");
        item.setContent(merged);
        item.setSummary("近期交互高频内容");
        item.setConfidence(0.45d);
        item.setImportance(0.5d);
        item.setSourceHistoryIds(new ArrayList<>(sourceHistoryIds));
        UserLongTermMemoryDoc memory = buildMemoryDoc(userId, conversationId, item, sourceHistoryIds, conversationByHistoryId);
        return memory == null ? List.of() : List.of(memory);
    }

    /**
     * 将模型提炼出的单条结构化结果转换成长期记忆文档。
     *
     * @param userId 当前用户 id。
     * @param conversationId 会话 id。
     * @param item 模型提炼出的单条记忆项。
     * @param sourceHistoryIds 可引用的历史消息 id 集合。
     * @param conversationByHistoryId historyId 到 conversationId 的映射。
     * @return 长期记忆文档；内容非法时返回 null。
     */
    private UserLongTermMemoryDoc buildMemoryDoc(Long userId,
                                                 String conversationId,
                                                 UserLongTermMemoryExtractionItem item,
                                                 Set<Long> sourceHistoryIds,
                                                 Map<Long, String> conversationByHistoryId) {
        if (item == null) {
            return null;
        }
        String memoryType = Objects.toString(item.getMemoryType(), "").trim();
        memoryType = normalizeMemoryType(memoryType);
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

    /**
     * 将长期记忆实体和来源关系转换成前端使用的 VO。
     *
     * @param memory 长期记忆实体。
     * @param sources 对应来源关系列表。
     * @return 长期记忆 VO。
     */
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

    /**
     * 从来源关系中整理出去重后的来源会话 id 列表。
     *
     * @param sources 来源关系列表。
     * @return 来源会话 id 列表。
     */
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

    /**
     * 读取当前用户的宠物名称；若未设置则回退到默认值。
     *
     * @param userId 当前用户 id。
     * @return 宠物名称。
     */
    private String resolvePetName(Long userId) {
        String petName = stringRedisTemplate.opsForValue().get(USER_MEMORY_PET_NAME_KEY + userId);
        if (StringUtils.isNotBlank(petName)) {
            stringRedisTemplate.expire(USER_MEMORY_PET_NAME_KEY + userId, 30, java.util.concurrent.TimeUnit.DAYS);
            return petName;
        }
        return properties.getDefaultPetName();
    }

    /**
     * 统计当前用户还有多少待学习会话。
     *
     * @param userId 当前用户 id。
     * @return 待学习会话数量。
     */
    private int resolvePendingConversationCount(Long userId) {
        return metadataService.listPendingConversationStates(userId).size();
    }

    /**
     * 记录最近一次完成长期记忆学习的时间。
     *
     * @param userId 当前用户 id。
     */
    private void markLearned(Long userId) {
        stringRedisTemplate.opsForValue().set(USER_MEMORY_LAST_LEARNED_AT_KEY + userId, java.time.Instant.now().toString());
    }

    /**
     * 读取当前用户最近一次完成长期记忆学习的时间。
     *
     * @param userId 当前用户 id。
     * @return 最近学习时间；不存在或解析失败时返回 null。
     */
    private LocalDateTime resolveLastLearnedAt(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(USER_MEMORY_LAST_LEARNED_AT_KEY + userId);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return java.time.Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据待学习状态和最近学习时间推导宠物当前情绪。
     *
     * @param userId 当前用户 id。
     * @return `learning`、`updated` 或 `idle`。
     */
    private String resolvePetMood(Long userId) {
        if (resolvePendingConversationCount(userId) > 0) {
            return "learning";
        }
        LocalDateTime lastLearnedAt = resolveLastLearnedAt(userId);
        if (lastLearnedAt != null && lastLearnedAt.isAfter(LocalDateTime.now().minusMinutes(properties.getUpdatedMoodMinutes()))) {
            return "updated";
        }
        return "idle";
    }

    /**
     * 校验 `validate User Id` 对应内容。
     *
     * @param userId userId 参数。
     */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    /**
     * 规范化模型返回的 historyId 列表，并过滤掉无效 id。
     *
     * @param sourceHistoryIds 模型返回的 historyId 列表。
     * @param allowedHistoryIds 当前会话允许引用的 historyId 集合。
     * @return 清洗后的 historyId 列表。
     */
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

    /**
     * 根据有效 historyId 列表筛出对应的来源会话映射。
     *
     * @param historyIds 有效 historyId 列表。
     * @param conversationByHistoryId historyId 到 conversationId 的映射。
     * @return 过滤后的映射结果。
     */
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

    /**
     * 为一条新长期记忆生成唯一 id。
     *
     * @param userId 当前用户 id。
     * @return 长期记忆 id。
     */
    private String buildMemoryId(Long userId) {
        return userId + "_" + IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 归一化模型返回的记忆类型标签。
     *
     * @param memoryType 原始类型文本。
     * @return 归一化后的记忆类型；不支持时返回空字符串。
     */
    private String normalizeMemoryType(String memoryType) {
        String normalized = Objects.toString(memoryType, "").trim().toUpperCase();
        if ("IDENTITY".equals(normalized) || "USER_INFO".equals(normalized) || "PROFILE".equals(normalized)) {
            return "USER_PROFILE";
        }
        if ("FACT".equals(normalized) || "OBJECTIVE_FACT".equals(normalized)) {
            return "FACT";
        }
        if ("PREFERENCE".equals(normalized) || "BEHAVIOR_PREFERENCE".equals(normalized) || "PREF".equals(normalized)) {
            return "PREFERENCE";
        }
        return SUPPORTED_MEMORY_TYPES.contains(normalized) ? normalized : "";
    }

    /**
     * 将文本压缩到指定长度，避免长期记忆内容超出配置限制。
     *
     * @param value 原始文本。
     * @param maxChars 最大字符数。
     * @return 截断后的文本。
     */
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

    /**
     * 将模型返回的数值安全转换成 double。
     *
     * @param value 原始值。
     * @param defaultValue 转换失败时的默认值。
     * @return 转换后的 double 值。
     */
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
