package com.shenchen.cloudcoldagent.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.mapper.HitlCheckpointMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.utils.HitlSerializationUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class HitlCheckpointServiceImpl extends ServiceImpl<HitlCheckpointMapper, HitlCheckpoint>
        implements HitlCheckpointService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final ChatConversationService chatConversationService;

    public HitlCheckpointServiceImpl(ChatConversationService chatConversationService) {
        this.chatConversationService = chatConversationService;
    }

    @Override
    public HitlCheckpointVO createCheckpoint(String conversationId, String agentType,
                                             List<PendingToolCall> pendingToolCalls,
                                             List<Message> checkpointMessages,
                                             Map<String, Object> context) {
        String normalizedConversationId = validateConversation(conversationId);
        if (pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "pendingToolCalls 不能为空");
        }

        HitlCheckpoint checkpoint = HitlCheckpoint.builder()
                .conversationId(normalizedConversationId)
                .interruptId("hitl_" + UUID.randomUUID().toString().replace("-", ""))
                .agentType(agentType == null || agentType.isBlank() ? "unknown" : agentType.trim())
                .pendingToolCallsJson(HitlSerializationUtils.writeJson(pendingToolCalls))
                .checkpointMessagesJson(HitlSerializationUtils.writeMessages(checkpointMessages))
                .contextJson(HitlSerializationUtils.writeContext(context))
                .status(STATUS_PENDING)
                .isDelete(0)
                .build();
        this.save(checkpoint);
        return toVO(checkpoint);
    }

    @Override
    public HitlCheckpointVO getByInterruptId(String interruptId) {
        if (interruptId == null || interruptId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "interruptId 不能为空");
        }
        HitlCheckpoint checkpoint = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("interruptId", interruptId.trim())
                .eq("isDelete", 0));
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "HITL checkpoint 不存在");
        }
        return toVO(checkpoint);
    }

    @Override
    public HitlCheckpointVO getLatestPendingByConversationId(String conversationId) {
        String normalizedConversationId = validateConversation(conversationId);
        HitlCheckpoint checkpoint = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("status", STATUS_PENDING)
                .eq("isDelete", 0)
                .orderBy("id", false));
        if (checkpoint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "当前会话不存在待处理的 HITL checkpoint");
        }
        return toVO(checkpoint);
    }

    @Override
    public HitlCheckpointVO resolveCheckpoint(String interruptId, List<PendingToolCall> feedbacks) {
        HitlCheckpoint checkpoint = getCheckpointEntity(interruptId);
        if (!STATUS_PENDING.equals(checkpoint.getStatus())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该 HITL checkpoint 已处理");
        }
        checkpoint.setFeedbacksJson(HitlSerializationUtils.writeJson(feedbacks == null ? List.of() : feedbacks));
        checkpoint.setStatus(STATUS_RESOLVED);
        checkpoint.setResolvedTime(LocalDateTime.now());
        this.updateById(checkpoint);
        return toVO(checkpoint);
    }

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
        return toVO(checkpoint);
    }

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
