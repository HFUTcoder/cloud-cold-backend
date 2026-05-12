package com.shenchen.cloudcoldagent.service.hitl.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.enums.HitlCheckpointStatusEnum;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.mapper.hitl.HitlCheckpointMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.chat.ChatConversationService;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.utils.HitlSerializationUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * HITL checkpoint 服务实现，负责创建、查询、resolve、consume 和上下文补充。
 */
@Service
@Slf4j
public class HitlCheckpointServiceImpl extends ServiceImpl<HitlCheckpointMapper, HitlCheckpoint>
        implements HitlCheckpointService {

    private final ChatConversationService chatConversationService;

    /**
     * 注入 checkpoint 归属校验所需的会话服务。
     *
     * @param chatConversationService 会话业务服务。
     */
    public HitlCheckpointServiceImpl(ChatConversationService chatConversationService) {
        this.chatConversationService = chatConversationService;
    }

    /**
     * 为一次需要人工确认的工具调用创建 checkpoint。
     *
     * @param conversationId 会话 id。
     * @param agentType 触发中断的 Agent 类型。
     * @param pendingToolCalls 待确认的工具调用列表。
     * @param checkpointMessages 中断时需要保存的消息快照。
     * @param context 恢复执行所需的附加上下文。
     * @return 创建后的 checkpoint 视图对象。
     */
    @Override
    public HitlCheckpointVO createCheckpoint(String conversationId, String agentType,
                                             List<PendingToolCall> pendingToolCalls,
                                             List<Message> checkpointMessages,
                                             Map<String, Object> context) {
        String normalizedConversationId = validateConversation(conversationId);
        if (pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "pendingToolCalls 不能为空");
        }
        List<PendingToolCall> normalizedPendingToolCalls = normalizePendingToolCalls(pendingToolCalls);
        List<Message> normalizedCheckpointMessages = normalizeCheckpointMessages(
                checkpointMessages,
                pendingToolCalls,
                normalizedPendingToolCalls
        );

        HitlCheckpoint checkpoint = HitlCheckpoint.builder()
                .conversationId(normalizedConversationId)
                .interruptId("hitl_" + UUID.randomUUID().toString().replace("-", ""))
                .agentType(agentType == null || agentType.isBlank() ? "unknown" : agentType.trim())
                .pendingToolCallsJson(HitlSerializationUtils.writeJson(normalizedPendingToolCalls))
                .checkpointMessagesJson(HitlSerializationUtils.writeMessages(normalizedCheckpointMessages))
                .contextJson(HitlSerializationUtils.writeContext(context))
                .status(HitlCheckpointStatusEnum.PENDING.getValue())
                .isDelete(0)
                .build();
        this.save(checkpoint);
        log.info("已创建 HITL checkpoint，conversationId={}, interruptId={}, agentType={}, pendingToolCount={}, contextKeys={}",
                normalizedConversationId,
                checkpoint.getInterruptId(),
                checkpoint.getAgentType(),
                normalizedPendingToolCalls.size(),
                context == null ? List.of() : context.keySet());
        return toVO(checkpoint);
    }

    private List<PendingToolCall> normalizePendingToolCalls(List<PendingToolCall> pendingToolCalls) {
        List<PendingToolCall> normalized = new ArrayList<>();
        LinkedHashSet<String> usedIds = new LinkedHashSet<>();
        int sequence = 0;
        for (PendingToolCall pendingToolCall : pendingToolCalls) {
            if (pendingToolCall == null) {
                continue;
            }
            sequence++;
            String normalizedId = normalizePendingToolCallId(pendingToolCall.id(), usedIds, sequence);
            normalized.add(new PendingToolCall(
                    normalizedId,
                    pendingToolCall.name(),
                    pendingToolCall.arguments(),
                    pendingToolCall.result(),
                    pendingToolCall.description()
            ));
        }
        return normalized;
    }

    private String normalizePendingToolCallId(String originalId, LinkedHashSet<String> usedIds, int sequence) {
        String candidate = originalId == null ? "" : originalId.trim();
        if (candidate.isEmpty()) {
            candidate = "hitl_call_" + sequence + "_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (usedIds.add(candidate)) {
            return candidate;
        }
        String deduplicated = candidate + "_" + sequence + "_" + UUID.randomUUID().toString().replace("-", "");
        usedIds.add(deduplicated);
        return deduplicated;
    }

    private List<Message> normalizeCheckpointMessages(List<Message> checkpointMessages,
                                                      List<PendingToolCall> originalPendingToolCalls,
                                                      List<PendingToolCall> normalizedPendingToolCalls) {
        if (checkpointMessages == null || checkpointMessages.isEmpty()
                || originalPendingToolCalls == null || originalPendingToolCalls.isEmpty()
                || normalizedPendingToolCalls == null || normalizedPendingToolCalls.isEmpty()) {
            return checkpointMessages == null ? List.of() : List.copyOf(checkpointMessages);
        }
        Map<String, Integer> matchedCounts = new LinkedHashMap<>();
        List<Message> normalizedMessages = new ArrayList<>(checkpointMessages.size());
        for (Message message : checkpointMessages) {
            if (!(message instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMessage)
                    || !assistantMessage.hasToolCalls()) {
                normalizedMessages.add(message);
                continue;
            }
            List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> normalizedToolCalls = new ArrayList<>();
            boolean changed = false;
            for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                int matchIndex = findPendingToolCallIndex(toolCall, originalPendingToolCalls, matchedCounts);
                if (matchIndex >= 0 && matchIndex < normalizedPendingToolCalls.size()) {
                    PendingToolCall normalizedPendingToolCall = normalizedPendingToolCalls.get(matchIndex);
                    normalizedToolCalls.add(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                            normalizedPendingToolCall.id(),
                            toolCall.type(),
                            toolCall.name(),
                            toolCall.arguments()
                    ));
                    changed = true;
                } else {
                    normalizedToolCalls.add(toolCall);
                }
            }
            if (!changed) {
                normalizedMessages.add(message);
                continue;
            }
            normalizedMessages.add(org.springframework.ai.chat.messages.AssistantMessage.builder()
                    .content(assistantMessage.getText())
                    .toolCalls(normalizedToolCalls)
                    .build());
        }
        return normalizedMessages;
    }

    private int findPendingToolCallIndex(org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall,
                                         List<PendingToolCall> pendingToolCalls,
                                         Map<String, Integer> matchedCounts) {
        if (toolCall == null || pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            return -1;
        }
        String matchKey = buildPendingMatchKey(toolCall.id(), toolCall.name(), toolCall.arguments());
        int startIndex = matchedCounts.getOrDefault(matchKey, 0);
        for (int index = startIndex; index < pendingToolCalls.size(); index++) {
            PendingToolCall pendingToolCall = pendingToolCalls.get(index);
            if (pendingToolCall == null) {
                continue;
            }
            if (!matchesPendingToolCall(toolCall, pendingToolCall)) {
                continue;
            }
            matchedCounts.put(matchKey, index + 1);
            return index;
        }
        return -1;
    }

    private boolean matchesPendingToolCall(org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall,
                                           PendingToolCall pendingToolCall) {
        if (toolCall == null || pendingToolCall == null) {
            return false;
        }
        if (Objects.equals(toolCall.id(), pendingToolCall.id())
                && Objects.equals(toolCall.name(), pendingToolCall.name())
                && Objects.equals(toolCall.arguments(), pendingToolCall.arguments())) {
            return true;
        }
        return Objects.equals(toolCall.name(), pendingToolCall.name())
                && Objects.equals(toolCall.arguments(), pendingToolCall.arguments());
    }

    private String buildPendingMatchKey(String id, String name, String arguments) {
        return String.valueOf(id) + "|" + String.valueOf(name) + "|" + String.valueOf(arguments);
    }

    /**
     * 仅根据 interruptId 查询 checkpoint 详情。
     *
     * @param interruptId 中断 id。
     * @return checkpoint 详情。
     */
    @Override
    public HitlCheckpointVO getByInterruptId(String interruptId) {
        return toVO(getCheckpointEntity(interruptId));
    }

    /**
     * 查询某个用户可访问的 checkpoint 详情。
     *
     * @param userId 用户 id。
     * @param interruptId 中断 id。
     * @return checkpoint 详情。
     */
    @Override
    public HitlCheckpointVO getByInterruptId(Long userId, String interruptId) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        validateCheckpointOwner(userId, checkpoint);
        return toVO(checkpoint);
    }

    /**
     * 查询某个会话最近一次仍处于待处理状态的 checkpoint。
     *
     * @param conversationId 会话 id。
     * @return 最近一次待处理 checkpoint。
     */
    @Override
    public HitlCheckpointVO getLatestPendingByConversationId(String conversationId) {
        String normalizedConversationId = validateConversation(conversationId);
        HitlCheckpoint checkpoint = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("status", HitlCheckpointStatusEnum.PENDING.getValue())
                .eq("isDelete", 0)
                .orderBy("id", false));
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "当前会话不存在待处理的 HITL checkpoint");
        }
        return toVO(checkpoint);
    }

    /**
     * 查询当前用户在某个会话下最近一次待处理的 checkpoint。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 最近一次待处理 checkpoint。
     */
    @Override
    public HitlCheckpointVO getLatestPendingByConversationId(Long userId, String conversationId) {
        String normalizedConversationId = validateConversation(conversationId);
        validateConversationOwner(userId, normalizedConversationId);
        HitlCheckpoint checkpoint = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("status", HitlCheckpointStatusEnum.PENDING.getValue())
                .eq("isDelete", 0)
                .orderBy("id", false));
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "当前会话不存在待处理的 HITL checkpoint");
        }
        return toVO(checkpoint);
    }

    /**
     * 根据 interruptId 提交某个 checkpoint 的审批结果。
     *
     * @param interruptId 中断 id。
     * @param feedbacks 用户反馈列表。
     * @return 更新后的 checkpoint 视图。
     */
    @Override
    public HitlCheckpointVO resolveCheckpoint(String interruptId, List<PendingToolCall> feedbacks) {
        return resolveCheckpointEntity(interruptId, feedbacks);
    }

    /**
     * 以带用户权限校验的方式提交 checkpoint 审批结果。
     *
     * @param userId 当前用户 id。
     * @param interruptId 中断 id。
     * @param feedbacks 用户反馈列表。
     * @return 更新后的 checkpoint 视图。
     */
    @Override
    public HitlCheckpointVO resolveCheckpoint(Long userId, String interruptId, List<PendingToolCall> feedbacks) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        validateCheckpointOwner(userId, checkpoint);
        return resolveCheckpointEntity(interruptId, feedbacks);
    }

    /**
     * 以 CAS 方式将 checkpoint 标记为已 resolve，并写入用户反馈。
     * 通过 WHERE status=PENDING 保证并发安全，防止同一 checkpoint 被重复 resolve。
     *
     * @param interruptId 中断 id。
     * @param feedbacks 用户反馈列表。
     * @return 更新后的 checkpoint 视图。
     */
    private HitlCheckpointVO resolveCheckpointEntity(String interruptId, List<PendingToolCall> feedbacks) {
        String feedbacksJson = HitlSerializationUtils.writeJson(feedbacks == null ? List.of() : feedbacks);
        int updatedRows = this.mapper.updateByQuery(
                HitlCheckpoint.builder()
                        .feedbacksJson(feedbacksJson)
                        .status(HitlCheckpointStatusEnum.RESOLVED.getValue())
                        .resolvedTime(LocalDateTime.now())
                        .build(),
                QueryWrapper.create()
                        .eq("interruptId", interruptId)
                        .eq("status", HitlCheckpointStatusEnum.PENDING.getValue())
                        .eq("isDelete", 0)
        );
        if (updatedRows <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该 HITL checkpoint 已处理或不存在");
        }
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        log.info("已提交 HITL 处理结果，interruptId={}, conversationId={}, feedbackCount={}",
                checkpoint.getInterruptId(),
                checkpoint.getConversationId(),
                feedbacks == null ? 0 : feedbacks.size());
        return toVO(checkpoint);
    }

    /**
     * 消费一个已 resolve 的 checkpoint，并将其推进到 consumed 状态。
     *
     * @param interruptId 中断 id。
     * @return 被消费后的 checkpoint 视图。
     */
    @Override
    public HitlCheckpointVO consumeResolvedCheckpoint(String interruptId) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        return consumeResolvedCheckpointEntity(checkpoint);
    }

    /**
     * 以带用户权限校验的方式消费一个已 resolve 的 checkpoint。
     *
     * @param userId 当前用户 id。
     * @param interruptId 中断 id。
     * @return 被消费后的 checkpoint 视图。
     */
    @Override
    public HitlCheckpointVO consumeResolvedCheckpoint(Long userId, String interruptId) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        validateCheckpointOwner(userId, checkpoint);
        return consumeResolvedCheckpointEntity(checkpoint);
    }

    /**
     * 将 checkpoint 从 resolved 推进到 consumed，防止重复恢复。
     *
     * @param checkpoint checkpoint 实体。
     * @return 被消费后的 checkpoint 视图。
     */
    private HitlCheckpointVO consumeResolvedCheckpointEntity(HitlCheckpoint checkpoint) {
        if (!HitlCheckpointStatusEnum.RESOLVED.getValue().equals(checkpoint.getStatus())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 checkpoint 尚未 resolve，不能 resume");
        }
        int updatedRows = this.mapper.updateByQuery(
                HitlCheckpoint.builder()
                        .status(HitlCheckpointStatusEnum.CONSUMED.getValue())
                        .build(),
                QueryWrapper.create()
                        .eq("id", checkpoint.getId())
                        .eq("status", HitlCheckpointStatusEnum.RESOLVED.getValue())
                        .eq("isDelete", 0)
        );
        if (updatedRows <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 checkpoint 已被 resume，不能重复执行");
        }
        checkpoint.setStatus(HitlCheckpointStatusEnum.CONSUMED.getValue());
        log.info("HITL checkpoint 已被消费，interruptId={}, conversationId={}",
                checkpoint.getInterruptId(),
                checkpoint.getConversationId());
        return toVO(checkpoint);
    }

    /**
     * 读取某个中断点保存的工具调用、消息快照和恢复上下文。
     *
     * @param interruptId 中断 id。
     * @return 可用于恢复执行的中断上下文对象。
     */
    @Override
    public AgentInterrupted loadInterrupted(String interruptId) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        return new AgentInterrupted(
                HitlSerializationUtils.readJson(checkpoint.getPendingToolCallsJson(), new TypeReference<>() {
                }),
                HitlSerializationUtils.readMessages(checkpoint.getCheckpointMessagesJson()),
                HitlSerializationUtils.readContext(checkpoint.getContextJson())
        );
    }

    /**
     * 为某个 checkpoint 追加恢复执行所需的上下文信息。
     *
     * @param interruptId 中断 id。
     * @param additionalContext 需要追加的上下文字段。
     * @return 更新后的 checkpoint 视图。
     */
    @Override
    public HitlCheckpointVO appendContext(String interruptId, Map<String, Object> additionalContext) {
        if (additionalContext == null || additionalContext.isEmpty()) {
            return getByInterruptId(interruptId);
        }
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        Map<String, Object> mergedContext = HitlSerializationUtils.readContext(checkpoint.getContextJson());
        mergedContext.putAll(additionalContext);
        checkpoint.setContextJson(HitlSerializationUtils.writeContext(mergedContext));
        this.updateById(checkpoint);
        log.info("已追加 HITL 上下文，interruptId={}, conversationId={}, appendedKeys={}",
                checkpoint.getInterruptId(),
                checkpoint.getConversationId(),
                additionalContext.keySet());
        return toVO(checkpoint);
    }

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean deleteByConversationId(String conversationId) {
        String normalizedConversationId = validateConversation(conversationId);
        return this.mapper.updateByQuery(
                HitlCheckpoint.builder()
                        .isDelete(1)
                        .build(),
                QueryWrapper.create()
                        .eq("conversationId", normalizedConversationId)
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 按 interruptId 查询 checkpoint 实体。
     *
     * @param interruptId 中断 id。
     * @return checkpoint 实体。
     */
    private HitlCheckpoint getCheckpointEntity(String interruptId) {
        if (interruptId == null || interruptId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "interruptId 不能为空");
        }
        HitlCheckpoint checkpoint = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("interruptId", interruptId.trim())
                .eq("isDelete", 0));
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        return checkpoint;
    }

    /**
     * 校验 `validate Conversation` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    private String validateConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        ChatConversation conversation = this.chatConversationService.getOne(QueryWrapper.create()
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        return conversation.getConversationId();
    }

    /**
     * 校验 `validate Checkpoint Owner` 对应内容。
     *
     * @param userId userId 参数。
     * @param checkpoint checkpoint 参数。
     */
    private void validateCheckpointOwner(Long userId, HitlCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        validateConversationOwner(userId, checkpoint.getConversationId());
    }

    /**
     * 校验 `validate Conversation Owner` 对应内容。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    private void validateConversationOwner(Long userId, String conversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (!chatConversationService.isConversationOwnedByUser(userId, conversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该 HITL checkpoint");
        }
    }

    /**
     * 将 checkpoint 实体转换成对外返回的 VO。
     *
     * @param checkpoint checkpoint 实体。
     * @return checkpoint 视图对象。
     */
    private HitlCheckpointVO toVO(HitlCheckpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        List<PendingToolCall> pendingToolCalls = HitlSerializationUtils.readJson(
                checkpoint.getPendingToolCallsJson(), new TypeReference<>() {
                }
        );
        List<PendingToolCall> feedbacks = HitlSerializationUtils.readJson(
                checkpoint.getFeedbacksJson(), new TypeReference<>() {
                }
        );
        // 对外接口返回 context 时，必须保证是 Jackson 可序列化结构；
        // 不能直接用 readContext（它会恢复出 HITLState 对象，导致序列化异常）。
        Map<String, Object> responseContext = HitlSerializationUtils.readJson(
                checkpoint.getContextJson(),
                new TypeReference<>() {
                }
        );
        if (responseContext == null) {
            responseContext = new LinkedHashMap<>();
        }

        return HitlCheckpointVO.builder()
                .id(checkpoint.getId())
                .conversationId(checkpoint.getConversationId())
                .interruptId(checkpoint.getInterruptId())
                .agentType(checkpoint.getAgentType())
                .pendingToolCalls(pendingToolCalls == null ? List.of() : pendingToolCalls)
                .checkpointMessages(HitlSerializationUtils.readMessages(checkpoint.getCheckpointMessagesJson()))
                .context(responseContext)
                .feedbacks(feedbacks == null ? List.of() : feedbacks)
                .status(checkpoint.getStatus())
                .resolvedTime(checkpoint.getResolvedTime())
                .createTime(checkpoint.getCreateTime())
                .updateTime(checkpoint.getUpdateTime())
                .build();
    }
}
