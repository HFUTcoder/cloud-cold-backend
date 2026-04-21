package com.shenchen.cloudcoldagent.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ChatConversationMapper;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话服务实现
 */
@Service
@Slf4j
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ChatConversationService {

    private static final String DEFAULT_CONVERSATION_TITLE = "新会话";

    private final SkillRegistry skillRegistry;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatMemoryHistoryMapper chatMemoryHistoryMapper;
    private final JdbcTemplate jdbcTemplate;

    public ChatConversationServiceImpl(SkillRegistry skillRegistry,
                                       ChatMemoryRepository chatMemoryRepository,
                                       ChatMemoryHistoryMapper chatMemoryHistoryMapper,
                                       JdbcTemplate jdbcTemplate) {
        this.skillRegistry = skillRegistry;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemoryHistoryMapper = chatMemoryHistoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        return listByUserId(userId).stream()
                .map(ChatConversation::getConversationId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ChatConversation> listByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .orderBy("lastActiveTime", false)
                .orderBy("id", false);
        List<ChatConversation> conversations = this.mapper.selectListByQuery(queryWrapper);
        conversations.forEach(this::fillSelectedSkillList);
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
                    .userId(userId)
                    .conversationId(normalizedConversationId)
                    .title(DEFAULT_CONVERSATION_TITLE)
                    .selectedSkills("[]")
                    .lastActiveTime(now)
                    .isDelete(0)
                    .build();
            this.save(conversation);
            return;
        }

        if (!userId.equals(existing.getUserId())) {
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
            if (!userId.equals(existing.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话归属用户不匹配");
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = ChatConversation.builder()
                .userId(userId)
                .conversationId(conversationId)
                .title(DEFAULT_CONVERSATION_TITLE)
                .selectedSkills("[]")
                .lastActiveTime(now)
                .isDelete(0)
                .build();
        this.save(conversation);
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
        if (!userId.equals(existing.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除该会话");
        }

        // 级联删除会话下所有记忆消息
        chatMemoryRepository.deleteByConversationId(normalizedConversationId);

        return jdbcTemplate.update("DELETE FROM chat_conversation WHERE id = ?", existing.getId()) > 0;
    }

    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("userId", userId)
                .eq("isDelete", 0);
        return this.mapper.selectCountByQuery(queryWrapper) > 0;
    }

    @Override
    public ChatConversation getByConversationId(Long userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(userId, conversationId);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("userId", userId)
                .eq("isDelete", 0);
        ChatConversation conversation = this.mapper.selectOneByQuery(queryWrapper);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        fillSelectedSkillList(conversation);
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
        conversation.setSelectedSkills(JSONUtil.toJsonStr(normalizedSkills));
        conversation.setSelectedSkillList(normalizedSkills);
        this.updateById(conversation);
        log.info("更新会话绑定 skills 完成，userId={}, conversationId={}, finalSkills={}",
                userId,
                conversation.getConversationId(),
                normalizedSkills);
    }

    @Override
    public String buildConversationSkillPrompt(Long userId, String conversationId) {
        ChatConversation conversation = getByConversationId(userId, conversationId);
        List<String> selectedSkillList = conversation.getSelectedSkillList();
        if (selectedSkillList == null || selectedSkillList.isEmpty()) {
            log.info("会话未绑定 skills，跳过生成 skill prompt，userId={}, conversationId={}",
                    userId,
                    conversation.getConversationId());
            return "";
        }

        String skillLines = selectedSkillList.stream()
                .map(skill -> "- " + skill)
                .collect(Collectors.joining("\n"));
        String frameworkInstructions = skillRegistry.getSkillLoadInstructions();

        String prompt = """
                当前会话绑定了以下高优先级 skills：
                %s

                请先结合用户问题判断上述 skills 中哪些真正相关。
                如果用户当前问题需要依赖这些已绑定 skill 的正文、reference、script 或其他资源内容才能回答，而这些内容尚未进入上下文，请先调用相关 skill 工具读取，再回答。
                在已绑定 skill 的前提下，不要仅仅因为“当前上下文里还没展开具体内容”就直接回答“信息不足”或“缺少上下文”。
                不要为了例行检查而一次性读取所有 skill 的完整内容。
                只有当某个已绑定 skill 与当前问题相关，或者你确实需要它的详细步骤、约束、脚本、参考材料时，才调用 read_skill 继续读取该 skill 的完整内容。
                一旦确认某个已绑定 skill 相关，后续回答必须优先遵循该 skill 的约束、步骤和要求。
                除了上述已绑定 skills 之外，不要主动加载其他 skill，除非系统另有明确要求。

                以下是系统提供的 skill 渐进式加载规范，请在“仅限上述 skills 范围内”遵循：
                %s
                """.formatted(skillLines, frameworkInstructions);
        log.info("生成会话级 skill prompt，userId={}, conversationId={}, selectedSkills={}, prompt=\n{}",
                userId,
                conversation.getConversationId(),
                selectedSkillList,
                prompt);
        return prompt;
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
        if (!userId.equals(conversation.getUserId())) {
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

    private void fillSelectedSkillList(ChatConversation conversation) {
        if (conversation == null) {
            return;
        }
        conversation.setSelectedSkillList(parseSelectedSkills(conversation.getSelectedSkills()));
    }

    private List<String> parseSelectedSkills(String selectedSkillsJson) {
        if (selectedSkillsJson == null || selectedSkillsJson.isBlank()) {
            return new ArrayList<>();
        }
        return JSONUtil.toList(JSONUtil.parseArray(selectedSkillsJson), String.class);
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
