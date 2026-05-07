package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import java.util.Map;

/**
 * 创建 `PlanTask` 实例。
 *
 * @param id id 参数。
 * @param toolName toolName 参数。
 * @param arguments arguments 参数。
 * @param order order 参数。
 * @param summary summary 参数。
 */
/**
 * `PlanTask` 记录对象。
 */
public record PlanTask(String id,
                       String toolName,
                       Map<String, Object> arguments,
                       int order,
                       String summary) {
}
