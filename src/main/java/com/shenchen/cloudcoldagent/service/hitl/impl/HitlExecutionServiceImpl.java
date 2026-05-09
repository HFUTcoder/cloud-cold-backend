package com.shenchen.cloudcoldagent.service.hitl.impl;

import com.shenchen.cloudcoldagent.service.hitl.HitlExecutionService;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * HITL 执行开关服务实现，用于判断当前会话是否需要启用人工确认机制。
 */
@Service
public class HitlExecutionServiceImpl implements HitlExecutionService {

    /**
     * 判断当前请求上下文是否满足启用 HITL 的基础条件。
     *
     * @param conversationId 会话 id。
     * @param interceptToolNames 需要被拦截确认的工具名称集合。
     * @return 会话 id 和拦截工具集合都有效时返回 true。
     */
    @Override
    public boolean isEnabled(String conversationId, Set<String> interceptToolNames) {
        return conversationId != null
                && !conversationId.isBlank()
                && interceptToolNames != null
                && !interceptToolNames.isEmpty();
    }
}
