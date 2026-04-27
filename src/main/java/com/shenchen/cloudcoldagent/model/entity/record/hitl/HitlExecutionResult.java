package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;

public record HitlExecutionResult(
        boolean interrupted,
        String content,
        String error,
        HitlCheckpointVO checkpoint
) {
}
