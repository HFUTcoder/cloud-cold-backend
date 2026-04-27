package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;

public record TaskResult(
        String taskId,
        boolean success,
        boolean interrupted,
        String output,
        String error,
        HitlCheckpointVO checkpoint) {
}
