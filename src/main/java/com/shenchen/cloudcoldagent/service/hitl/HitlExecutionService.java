package com.shenchen.cloudcoldagent.service.hitl;

import java.util.Set;

/**
 * `HitlExecutionService` 接口定义。
 */
public interface HitlExecutionService {

    boolean isEnabled(String conversationId, Set<String> interceptToolNames);
}
