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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Agent 对话入口控制层，负责接收调用 / 恢复请求并以 SSE 形式向前端输出事件流。
 */
@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentController {

    private final AgentService agentService;

    private final UserService userService;

    private final ChatConversationService chatConversationService;

    /**
     * 注入 Agent 调用和会话处理所需的服务。
     *
     * @param agentService Agent 业务服务。
     * @param userService 用户业务服务。
     * @param chatConversationService 会话业务服务。
     */
    public AgentController(AgentService agentService,
                           UserService userService,
                           ChatConversationService chatConversationService) {
        this.agentService = agentService;
        this.userService = userService;
        this.chatConversationService = chatConversationService;
    }

    /**
     * 发起一次新的 Agent 调用；如未携带会话 id，则先为当前用户自动创建会话。
     *
     * @param agentCallRequest Agent 调用请求体。
     * @param request 当前 HTTP 请求。
     * @return 用于向前端持续推送 Agent 事件的 SSE emitter。
     */
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

    /**
     * 根据中断 id 恢复一次被 HITL 暂停的 Agent 执行。
     *
     * @param agentResumeRequest Agent 恢复请求体。
     * @param request 当前 HTTP 请求。
     * @return 用于向前端持续推送恢复后事件的 SSE emitter。
     */
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

        Disposable disposable = agentService.resume(agentResumeRequest.getInterruptId(), loginUser.getId()).subscribe(
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

    /**
     * 向前端发送单条 Agent SSE 事件。
     *
     * @param emitter 当前 SSE 通道。
     * @param data 待发送的 Agent 事件。
     */
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

    /**
     * 计算从开始时间到当前的耗时毫秒数。
     *
     * @param startNanos 开始时记录的纳秒时间戳。
     * @return 已过去的毫秒数。
     */
    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
