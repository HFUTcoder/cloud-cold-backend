package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 服务接口，定义对话调用和 HITL 恢复两条主链路。
 */
public interface AgentService {

    /**
     * 发起一次新的 Agent 调用。
     *
     * @param agentCallRequest Agent 调用请求。
     * @param userId 当前用户 id。
     * @return 面向前端的 Agent 事件流。
     */
    Flux<AgentStreamEvent> call(AgentCallRequest agentCallRequest, Long userId);

    /**
     * 恢复一次被 HITL 中断的 Agent 执行。
     *
     * @param interruptId 中断 id。
     * @param userId 当前用户 id。
     * @return 恢复执行后的 Agent 事件流。
     */
    Flux<AgentStreamEvent> resume(String interruptId, Long userId);
}
