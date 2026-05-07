package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import java.util.Map;

/**
 * 创建 `ExecutedTaskSnapshot` 实例。
 *
 * @param taskId taskId 参数。
 * @param toolName toolName 参数。
 * @param arguments arguments 参数。
 * @param summary summary 参数。
 * @param success success 参数。
 * @param output output 参数。
 * @param error error 参数。
 * @param round round 参数。
 */
/**
 * `ExecutedTaskSnapshot` 记录对象。
 */
public record ExecutedTaskSnapshot(
        String taskId,
        String toolName,
        Map<String, Object> arguments,
        String summary,
        boolean success,
        String output,
        String error,
        int round) {
}
