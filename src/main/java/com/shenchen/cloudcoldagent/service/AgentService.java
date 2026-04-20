package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import reactor.core.publisher.Flux;

/**
 * 代理服务层
 *
 */
public interface AgentService {

    /**
     * 调用代理
     *
     * @param agentCallRequest 代理调用请求
     * @return 响应流
     */
    Flux<String> call(AgentCallRequest agentCallRequest, Long userId);
}
