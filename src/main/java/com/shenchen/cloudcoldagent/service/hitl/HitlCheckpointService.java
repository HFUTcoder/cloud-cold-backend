package com.shenchen.cloudcoldagent.service.hitl;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.entity.hitl.HitlCheckpoint;
import com.shenchen.cloudcoldagent.model.vo.hitl.HitlCheckpointVO;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * `HitlCheckpointService` 接口定义。
 */
public interface HitlCheckpointService extends IService<HitlCheckpoint> {

    HitlCheckpointVO createCheckpoint(String conversationId, String agentType,
                                      List<PendingToolCall> pendingToolCalls,
                                      List<Message> checkpointMessages,
                                      Map<String, Object> context);

    HitlCheckpointVO getByInterruptId(String interruptId);

    HitlCheckpointVO getByInterruptId(Long userId, String interruptId);

    HitlCheckpointVO getLatestPendingByConversationId(String conversationId);

    HitlCheckpointVO getLatestPendingByConversationId(Long userId, String conversationId);

    HitlCheckpointVO resolveCheckpoint(String interruptId, List<PendingToolCall> feedbacks);

    HitlCheckpointVO resolveCheckpoint(Long userId, String interruptId, List<PendingToolCall> feedbacks);

    HitlCheckpointVO consumeResolvedCheckpoint(String interruptId);

    HitlCheckpointVO consumeResolvedCheckpoint(Long userId, String interruptId);

    AgentInterrupted loadInterrupted(String interruptId);

    HitlCheckpointVO appendContext(String interruptId, Map<String, Object> additionalContext);

    boolean deleteByConversationId(String conversationId);
}
