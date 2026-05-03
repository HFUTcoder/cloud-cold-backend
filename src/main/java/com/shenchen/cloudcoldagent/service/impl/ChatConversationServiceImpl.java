package com.shenchen.cloudcoldagent.service.impl;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ChatConversationMapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.mapper.HitlCheckpointMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.ConversationKnowledgeRelationService;
import com.shenchen.cloudcoldagent.service.ConversationSkillRelationService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.UserConversationRelationService;
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
 * 会话服务实现
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

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        return userConversationRelationService.listConversationIdsByUserId(userId);
    }

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

    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        return userConversationRelationService.isConversationOwnedByUser(userId, normalizedConversationId);
    }

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

    @Override
    public String buildConversationSkillPrompt(Long userId, String conversationId) {
        log.debug("buildConversationSkillPrompt 已废弃，当前 skill 前置能力统一由 skill workflow 负责，conversationId={}",
                normalizeConversationId(userId, conversationId));
        return "";
    }

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

    private void fillConversationBindings(Long userId, ChatConversation conversation) {
        if (conversation == null) {
            return;
        }
        conversation.setSelectedSkillList(new ArrayList<>(
                conversationSkillRelationService.listSkillNamesByUserIdAndConversationId(userId, conversation.getConversationId())
        ));
        fillSelectedKnowledge(userId, conversation);
    }

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
