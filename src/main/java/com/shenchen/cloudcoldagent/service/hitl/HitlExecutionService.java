package com.shenchen.cloudcoldagent.service.hitl;

import java.util.Set;

/**
 * `HitlExecutionService` 接口定义。
 */
public interface HitlExecutionService {

    /**
     * 判断 `is Enabled` 条件是否成立。
     *
     * @param conversationId conversationId 参数。
     * @param interceptToolNames interceptToolNames 参数。
     * @return 返回处理结果。
     */
    boolean isEnabled(String conversationId, Set<String> interceptToolNames);
}
