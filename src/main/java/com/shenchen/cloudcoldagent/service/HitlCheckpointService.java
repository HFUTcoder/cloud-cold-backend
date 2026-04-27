package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public interface HitlCheckpointService extends IService<HitlCheckpoint> {

    HitlCheckpointVO createCheckpoint(String conversationId, String agentType,
                                      List<PendingToolCall> pendingToolCalls,
                                      List<Message> checkpointMessages,
                                      Map<String, Object> context);

    HitlCheckpointVO getByInterruptId(String interruptId);

    HitlCheckpointVO getLatestPendingByConversationId(String conversationId);

    HitlCheckpointVO resolveCheckpoint(String interruptId, List<PendingToolCall> feedbacks);

    AgentInterrupted loadInterrupted(String interruptId);

    HitlCheckpointVO appendContext(String interruptId, Map<String, Object> additionalContext);
}
