package com.shenchen.cloudcoldagent.model.dto.hitl;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * `HitlCheckpointResolveRequest` 类型实现。
 */
@Data
public class HitlCheckpointResolveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String interruptId;

    private List<PendingToolCall> feedbacks;
}
