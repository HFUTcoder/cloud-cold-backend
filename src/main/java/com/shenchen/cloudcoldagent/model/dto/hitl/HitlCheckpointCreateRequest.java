package com.shenchen.cloudcoldagent.model.dto.hitl;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class HitlCheckpointCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String conversationId;

    private String agentType;

    private List<PendingToolCall> pendingToolCalls;

    private List<Message> checkpointMessages;

    private Map<String, Object> context;
}
