package com.shenchen.cloudcoldagent.service.chat.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.chat.ChatConversationMapper;
import com.shenchen.cloudcoldagent.mapper.chat.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.mapper.hitl.HitlCheckpointMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.registry.SkillRegistry;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import com.shenchen.cloudcoldagent.service.chat.ConversationKnowledgeRelationService;
import com.shenchen.cloudcoldagent.service.chat.ConversationSkillRelationService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.chat.UserConversationRelationService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话服务实现，负责会话创建、归属校验、绑定关系维护以及删除时的级联清理。
 */
@Service
@Slf4j
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ChatConversationService {

    private static final String DEFAULT_CONVERSATION_TITLE = "新会话";

    private final SkillRegistry skillRegistry;

    private final ChatConversationMapper chatConversationMapper;

    private final ChatMemoryRepository chatMemoryRepository;

    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;

    private final UserConversationRelationService userConversationRelationService;

    private final HitlCheckpointMapper hitlCheckpointMapper;

    private final ConversationKnowledgeRelationService conversationKnowledgeRelationService;

    private final ConversationSkillRelationService conversationSkillRelationService;

    private final KnowledgeService knowledgeService;

    private final UserLongTermMemoryService userLongTermMemoryService;

    /**
     * 注入会话主链路所需的依赖。
     *
     * @param skillRegistry skill 注册表。
     * @param chatConversationMapper 会话数据访问对象。
     * @param chatMemoryRepository 对话记忆仓库。
     * @param chatMemoryHistoryMapper 历史消息 mapper。
     * @param userConversationRelationService 用户会话归属服务。
     * @param hitlCheckpointMapper HITL checkpoint mapper。
     * @param conversationKnowledgeRelationService 会话知识库绑定服务。
     * @param conversationSkillRelationService 会话 skill 绑定服务。
     * @param knowledgeService 知识库业务服务。
     * @param userLongTermMemoryService 长期记忆业务服务。
     */
    public ChatConversationServiceImpl(SkillRegistry skillRegistry,
                                       ChatConversationMapper chatConversationMapper,
                                       ChatMemoryRepository chatMemoryRepository,
                                       ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                       UserConversationRelationService userConversationRelationService,
                                       HitlCheckpointMapper hitlCheckpointMapper,
                                       ConversationKnowledgeRelationService conversationKnowledgeRelationService,
                                       ConversationSkillRelationService conversationSkillRelationService,
                                       KnowledgeService knowledgeService,
                                       UserLongTermMemoryService userLongTermMemoryService) {
        this.skillRegistry = skillRegistry;
        this.chatConversationMapper = chatConversationMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.userConversationRelationService = userConversationRelationService;
        this.hitlCheckpointMapper = hitlCheckpointMapper;
        this.conversationKnowledgeRelationService = conversationKnowledgeRelationService;
        this.conversationSkillRelationService = conversationSkillRelationService;
        this.knowledgeService = knowledgeService;
        this.userLongTermMemoryService = userLongTermMemoryService;
    }

    /**
     * 查询某个用户拥有的全部会话 id。
     *
     * @param userId 用户 id。
     * @return 当前用户的会话 id 列表。
     */
    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        return userConversationRelationService.listConversationIdsByUserId(userId);
    }

    /**
     * 查询当前用户的会话列表，并补齐 skill / 知识库绑定信息。
     *
     * @param userId 用户 id。
     * @return 会话列表。
     */
    @Override
    public List<ChatConversation> listByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        List<String> conversationIds = listConversationIdsByUserId(userId);
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        List<ChatConversation> conversations = chatConversationMapper.selectListByQuery(QueryWrapper.create()
                .in("conversationId", conversationIds)
                .eq("isDelete", 0)
                .orderBy("lastActiveTime", false)
                .orderBy("id", false));
        conversations.forEach(conversation -> fillConversationBindings(userId, conversation));
        return conversations;
    }

    /**
     * 刷新会话活跃时间；若会话不存在则补建并绑定到当前用户。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     */
    @Override
    public void touchConversation(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ChatConversation conversation = ChatConversation.builder()
                    .conversationId(normalizedConversationId)
                    .title(DEFAULT_CONVERSATION_TITLE)
                    .lastActiveTime(now)
                    .isDelete(0)
                    .build();
            this.save(conversation);
            userConversationRelationService.bindUserConversation(userId, normalizedConversationId, now);
            return;
        }

        if (!isConversationOwnedByUser(userId, normalizedConversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
        }
        existing.setLastActiveTime(now);
        this.updateById(existing);
    }

    /**
     * 为当前用户显式创建一个新会话。
     *
     * @param userId 用户 id。
     * @return 新会话 id。
     */
    @Override
    public String createConversation(Long userId) {
        String conversationId = normalizeConversationId(userId, null);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", conversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);
        if (existing != null) {
            if (!isConversationOwnedByUser(userId, conversationId)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = ChatConversation.builder()
                .conversationId(conversationId)
                .title(DEFAULT_CONVERSATION_TITLE)
                .lastActiveTime(now)
                .isDelete(0)
                .build();
        this.save(conversation);
        userConversationRelationService.bindUserConversation(userId, conversationId, now);
        return conversationId;
    }

    /**
     * 删除会话并级联清理记忆、绑定关系、HITL checkpoint 和长期记忆侧状态。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 是否删除成功。
     */
    @Override
    public boolean deleteConversation(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation existing = this.mapper.selectOneByQuery(queryWrapper);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!isConversationOwnedByUser(userId, normalizedConversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除该会话");
        }

        // 级联删除会话下所有记忆消息
        chatMemoryRepository.deleteByConversationId(normalizedConversationId);

        userConversationRelationService.deleteByConversationId(normalizedConversationId);
        conversationKnowledgeRelationService.deleteByConversationId(normalizedConversationId);
        conversationSkillRelationService.deleteByConversationId(normalizedConversationId);
        hitlCheckpointMapper.updateByQuery(
                HitlCheckpoint.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("conversationId", normalizedConversationId)
                        .eq("isDelete", 0)
        );
        boolean deleted = this.mapper.updateByQuery(
                ChatConversation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("id", existing.getId())
                        .eq("isDelete", 0)
        ) > 0;
        if (deleted) {
            userLongTermMemoryService.onConversationDeleted(userId, normalizedConversationId);
        }
        return deleted;
    }

    /**
     * 判断某个会话是否归属于指定用户。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 会话归属匹配时返回 true。
     */
    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        return userConversationRelationService.isConversationOwnedByUser(userId, normalizedConversationId);
    }

    /**
     * 获取 `get By Conversation Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public ChatConversation getByConversationId(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        if (!isConversationOwnedByUser(userId, normalizedConversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该会话");
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation conversation = this.mapper.selectOneByQuery(queryWrapper);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        fillConversationBindings(userId, conversation);
        return conversation;
    }

    /**
     * 更新 `update Conversation Skills` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param selectedSkills selectedSkills 参数。
     */
    @Override
    public void updateConversationSkills(Long userId, String conversationId, List<String> selectedSkills) {
        ChatConversation conversation = getByConversationId(userId, conversationId);
        List<String> oldSkills = conversation.getSelectedSkillList() == null
                ? new ArrayList<>()
                : new ArrayList<>(conversation.getSelectedSkillList());
        List<String> normalizedSkills = normalizeSelectedSkills(selectedSkills);
        log.info("更新会话绑定 skills，userId={}, conversationId={}, oldSkills={}, requestedSkills={}, normalizedSkills={}",
                userId,
                conversation.getConversationId(),
                oldSkills,
                selectedSkills,
                normalizedSkills);
        conversationSkillRelationService.replaceSkills(userId, conversation.getConversationId(), normalizedSkills, LocalDateTime.now());
        conversation.setSelectedSkillList(normalizedSkills);
        log.info("更新会话绑定 skills 完成，userId={}, conversationId={}, finalSkills={}",
                userId,
                conversation.getConversationId(),
                normalizedSkills);
    }

    /**
     * 更新 `update Conversation Knowledge` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param knowledgeId knowledgeId 参数。
     */
    @Override
    public void updateConversationKnowledge(Long userId, String conversationId, Long knowledgeId) {
        ChatConversation conversation = getByConversationId(userId, conversationId);
        if (knowledgeId == null || knowledgeId <= 0) {
            conversationKnowledgeRelationService.unbindKnowledge(userId, conversation.getConversationId());
            conversation.setSelectedKnowledgeId(null);
            conversation.setSelectedKnowledgeName(null);
            log.info("解除会话绑定知识库，userId={}, conversationId={}", userId, conversation.getConversationId());
            return;
        }
        com.shenchen.cloudcoldagent.model.entity.Knowledge knowledge = knowledgeService.getKnowledgeById(userId, knowledgeId);
        conversationKnowledgeRelationService.bindKnowledge(userId, conversation.getConversationId(), knowledgeId, LocalDateTime.now());
        conversation.setSelectedKnowledgeId(knowledgeId);
        conversation.setSelectedKnowledgeName(knowledge.getKnowledgeName());
        log.info("更新会话绑定知识库，userId={}, conversationId={}, knowledgeId={}, knowledgeName={}",
                userId,
                conversation.getConversationId(),
                knowledgeId,
                knowledge.getKnowledgeName());
    }

    /**
     * 构建 `build Conversation Skill Prompt` 对应结果。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public String buildConversationSkillPrompt(Long userId, String conversationId) {
        log.debug("buildConversationSkillPrompt 已废弃，当前 skill 前置能力统一由 skill workflow 负责，conversationId={}",
                normalizeConversationId(userId, conversationId));
        return "";
    }

    /**
     * 处理 `normalize Conversation Id` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param rawConversationId rawConversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public String normalizeConversationId(Long userId, String rawConversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (rawConversationId == null || rawConversationId.isBlank()) {
            return "conv_" + UUID.randomUUID().toString().replace("-", "");
        }
        return rawConversationId.trim();
    }

    /**
     * 生成 `generate Title On First Message` 对应结果。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @param firstMessage firstMessage 参数。
     */
    @Override
    public void generateTitleOnFirstMessage(Long userId, String conversationId, String firstMessage) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper conversationQuery = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0);
        ChatConversation conversation = this.mapper.selectOneByQuery(conversationQuery);
        if (conversation == null) {
            return;
        }
        if (!isConversationOwnedByUser(userId, normalizedConversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
        }
        if (conversation.getTitle() != null && !DEFAULT_CONVERSATION_TITLE.equals(conversation.getTitle())) {
            return;
        }

        long messageCount = chatMemoryHistoryMapper.selectCountByQuery(QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0));
        if (messageCount > 0) {
            return;
        }

        String safeMessage = firstMessage == null ? "" : firstMessage.trim();
        if (safeMessage.isEmpty()) {
            return;
        }
        String generatedTitle = safeMessage.length() <= 5 ? safeMessage : safeMessage.substring(0, 5);
        conversation.setTitle(generatedTitle);
        this.updateById(conversation);
    }

    /**
     * 处理 `fill Conversation Bindings` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversation conversation 参数。
     */
    private void fillConversationBindings(Long userId, ChatConversation conversation) {
        if (conversation == null) {
            return;
        }
        conversation.setSelectedSkillList(new ArrayList<>(
                conversationSkillRelationService.listSkillNamesByUserIdAndConversationId(userId, conversation.getConversationId())
        ));
        fillSelectedKnowledge(userId, conversation);
    }

    /**
     * 处理 `fill Selected Knowledge` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversation conversation 参数。
     */
    private void fillSelectedKnowledge(Long userId, ChatConversation conversation) {
        if (conversation == null || userId == null || userId <= 0) {
            return;
        }
        var relation = conversationKnowledgeRelationService.getByUserIdAndConversationId(userId, conversation.getConversationId());
        if (relation == null || relation.getKnowledgeId() == null || relation.getKnowledgeId() <= 0) {
            conversation.setSelectedKnowledgeId(null);
            conversation.setSelectedKnowledgeName(null);
            return;
        }
        conversation.setSelectedKnowledgeId(relation.getKnowledgeId());
        try {
            var knowledge = knowledgeService.getKnowledgeById(userId, relation.getKnowledgeId());
            conversation.setSelectedKnowledgeName(knowledge.getKnowledgeName());
        } catch (BusinessException ex) {
            conversation.setSelectedKnowledgeName(null);
        }
    }

    /**
     * 处理 `normalize Selected Skills` 对应逻辑。
     *
     * @param selectedSkills selectedSkills 参数。
     * @return 返回处理结果。
     */
    private List<String> normalizeSelectedSkills(List<String> selectedSkills) {
        if (selectedSkills == null || selectedSkills.isEmpty()) {
            return new ArrayList<>();
        }
        return selectedSkills.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(skill -> !skill.isBlank())
                .peek(skill -> {
                    if (!skillRegistry.contains(skill)) {
                        throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 不存在: " + skill);
                    }
                })
                .distinct()
                .toList();
    }
}
