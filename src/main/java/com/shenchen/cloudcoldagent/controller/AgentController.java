package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @PostMapping("/call")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Flux<String> call(@RequestBody AgentCallRequest agentCallRequest) {
        ThrowUtils.throwIf(agentCallRequest == null, ErrorCode.PARAMS_ERROR);

        return agentService.call(agentCallRequest);
    }
}
