package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import java.util.Map;

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
