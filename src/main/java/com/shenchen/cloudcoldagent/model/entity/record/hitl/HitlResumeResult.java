package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import com.shenchen.cloudcoldagent.model.vo.hitl.HitlCheckpointVO;

/**
 * `HitlResumeResult` 记录对象。
 */
public record HitlResumeResult(
        boolean interrupted,
        String content,
        String error,
        HitlCheckpointVO checkpoint
) {
}
