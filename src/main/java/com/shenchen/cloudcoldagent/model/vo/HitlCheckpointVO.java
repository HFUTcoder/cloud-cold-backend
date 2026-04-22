package com.shenchen.cloudcoldagent.model.vo;

import com.shenchen.cloudcoldagent.hitl.PendingToolCall;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class HitlCheckpointVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String conversationId;

    private String interruptId;

    private String agentType;

    private List<PendingToolCall> pendingToolCalls;

    private List<Message> checkpointMessages;

    private Map<String, Object> context;

    private List<PendingToolCall> feedbacks;

    private String status;

    private LocalDateTime resolvedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
