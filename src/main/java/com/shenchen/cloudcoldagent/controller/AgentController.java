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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;

    private final UserService userService;

    private final ChatConversationService chatConversationService;

    @PostMapping("/call")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public SseEmitter call(@RequestBody AgentCallRequest agentCallRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(agentCallRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean autoCreatedConversation = false;
        if (agentCallRequest.getConversationId() == null || agentCallRequest.getConversationId().isBlank()) {
            String autoCreatedConversationId = chatConversationService.createConversation(loginUser.getId());
            agentCallRequest.setConversationId(autoCreatedConversationId);
            autoCreatedConversation = true;
        }

        SseEmitter emitter = new SseEmitter(0L);
        String conversationId = agentCallRequest.getConversationId();
        long startNanos = System.nanoTime();
        log.info("收到智能体调用请求，userId={}, conversationId={}, mode={}, questionLength={}, autoCreatedConversation={}",
                loginUser.getId(),
                conversationId,
                agentCallRequest.getMode(),
                agentCallRequest.getQuestion() == null ? 0 : agentCallRequest.getQuestion().length(),
                autoCreatedConversation);

        Disposable disposable = agentService.call(agentCallRequest, loginUser.getId()).subscribe(
                data -> sendEvent(emitter, data),
                throwable -> {
                    log.error("智能体调用执行失败，userId={}, conversationId={}, message={}",
                            loginUser.getId(),
                            conversationId,
                            throwable == null ? "" : String.valueOf(throwable.getMessage()),
                            throwable);
                    sendEvent(emitter, AgentStreamEventFactory.error(
                            conversationId,
                            null,
                            throwable == null ? "" : String.valueOf(throwable.getMessage())
                    ));
                    emitter.completeWithError(throwable);
                },
                () -> {
                    log.info("智能体调用完成，userId={}, conversationId={}, durationMs={}",
                            loginUser.getId(),
                            conversationId,
                            elapsedMillis(startNanos));
                    emitter.complete();
                }
        );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            log.warn("智能体调用超时，userId={}, conversationId={}, durationMs={}",
                    loginUser.getId(),
                    conversationId,
                    elapsedMillis(startNanos));
            emitter.complete();
        });
        emitter.onError(err -> {
            log.warn("智能体 SSE 连接异常结束，userId={}, conversationId={}, message={}",
                    loginUser.getId(),
                    conversationId,
                    err == null ? "" : String.valueOf(err.getMessage()));
            disposable.dispose();
        });

        return emitter;
    }

    @PostMapping("/resume")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public SseEmitter resume(@RequestBody AgentResumeRequest agentResumeRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(agentResumeRequest == null || agentResumeRequest.getInterruptId() == null
                || agentResumeRequest.getInterruptId().isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);

        SseEmitter emitter = new SseEmitter(0L);
        long startNanos = System.nanoTime();
        log.info("收到智能体恢复请求，userId={}, interruptId={}",
                loginUser.getId(),
                agentResumeRequest.getInterruptId());

        Disposable disposable = agentService.resume(agentResumeRequest.getInterruptId()).subscribe(
                data -> sendEvent(emitter, data),
                throwable -> {
                    log.error("智能体恢复执行失败，userId={}, interruptId={}, message={}",
                            loginUser.getId(),
                            agentResumeRequest.getInterruptId(),
                            throwable == null ? "" : String.valueOf(throwable.getMessage()),
                            throwable);
                    sendEvent(emitter, AgentStreamEventFactory.error(
                            null,
                            agentResumeRequest.getInterruptId(),
                            throwable == null ? "" : String.valueOf(throwable.getMessage())
                    ));
                    emitter.completeWithError(throwable);
                },
                () -> {
                    log.info("智能体恢复执行完成，userId={}, interruptId={}, durationMs={}",
                            loginUser.getId(),
                            agentResumeRequest.getInterruptId(),
                            elapsedMillis(startNanos));
                    emitter.complete();
                }
        );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            log.warn("智能体恢复执行超时，userId={}, interruptId={}, durationMs={}",
                    loginUser.getId(),
                    agentResumeRequest.getInterruptId(),
                    elapsedMillis(startNanos));
            emitter.complete();
        });
        emitter.onError(err -> {
            log.warn("智能体恢复 SSE 连接异常结束，userId={}, interruptId={}, message={}",
                    loginUser.getId(),
                    agentResumeRequest.getInterruptId(),
                    err == null ? "" : String.valueOf(err.getMessage()));
            disposable.dispose();
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AgentStreamEvent data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("agent")
                    .data(data));
        } catch (IOException e) {
            log.warn("发送 SSE 事件失败，eventType={}, conversationId={}, interruptId={}, message={}",
                    data == null ? null : data.getType(),
                    data == null ? null : data.getConversationId(),
                    data == null ? null : data.getInterruptId(),
                    e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
