package com.shenchen.cloudcoldagent.model.entity.record.hitl;

import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;

/**
 * 创建 `HitlResumeResult` 实例。
 *
 * @param interrupted interrupted 参数。
 * @param content content 参数。
 * @param error error 参数。
 * @param checkpoint checkpoint 参数。
 */
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
