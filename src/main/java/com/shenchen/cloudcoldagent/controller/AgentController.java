package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private UserService userService;

    @Autowired
    private ChatConversationService chatConversationService;

    @PostMapping("/call")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public SseEmitter call(@RequestBody AgentCallRequest agentCallRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(agentCallRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        if (agentCallRequest.getConversationId() == null || agentCallRequest.getConversationId().isBlank()) {
            String autoCreatedConversationId = chatConversationService.createConversation(loginUser.getId());
            agentCallRequest.setConversationId(autoCreatedConversationId);
        }

        SseEmitter emitter = new SseEmitter(0L);
        String conversationId = agentCallRequest.getConversationId();

        Disposable disposable = agentService.call(agentCallRequest, loginUser.getId()).subscribe(
                data -> sendEvent(emitter, data),
                throwable -> {
                    sendEvent(emitter, AgentStreamEventFactory.error(
                            conversationId,
                            null,
                            throwable == null ? "" : String.valueOf(throwable.getMessage())
                    ));
                    emitter.completeWithError(throwable);
                },
                emitter::complete
        );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            emitter.complete();
        });
        emitter.onError(err -> disposable.dispose());

        return emitter;
    }

    @PostMapping("/resume")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public SseEmitter resume(@RequestBody AgentResumeRequest agentResumeRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(agentResumeRequest == null || agentResumeRequest.getInterruptId() == null
                || agentResumeRequest.getInterruptId().isBlank(), ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(request);

        SseEmitter emitter = new SseEmitter(0L);

        Disposable disposable = agentService.resume(agentResumeRequest.getInterruptId()).subscribe(
                data -> sendEvent(emitter, data),
                throwable -> {
                    sendEvent(emitter, AgentStreamEventFactory.error(
                            null,
                            agentResumeRequest.getInterruptId(),
                            throwable == null ? "" : String.valueOf(throwable.getMessage())
                    ));
                    emitter.completeWithError(throwable);
                },
                emitter::complete
        );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            emitter.complete();
        });
        emitter.onError(err -> disposable.dispose());

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AgentStreamEvent data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("agent")
                    .data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
