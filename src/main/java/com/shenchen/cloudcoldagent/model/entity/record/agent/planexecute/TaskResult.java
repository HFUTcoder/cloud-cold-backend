package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;

/**
 * 创建 `TaskResult` 实例。
 *
 * @param taskId taskId 参数。
 * @param success success 参数。
 * @param interrupted interrupted 参数。
 * @param output output 参数。
 * @param error error 参数。
 * @param checkpoint checkpoint 参数。
 */
/**
 * `TaskResult` 记录对象。
 */
public record TaskResult(
        String taskId,
        boolean success,
        boolean interrupted,
        String output,
        String error,
        HitlCheckpointVO checkpoint) {
}
