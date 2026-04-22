package com.shenchen.cloudcoldagent.model.dto.hitl;

import com.shenchen.cloudcoldagent.hitl.PendingToolCall;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class HitlCheckpointResolveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String interruptId;

    private List<PendingToolCall> feedbacks;
}
