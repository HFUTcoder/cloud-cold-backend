package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import java.util.Map;

public record PlanTask(String id,
                       String toolName,
                       Map<String, Object> arguments,
                       int order,
                       String summary) {
}
