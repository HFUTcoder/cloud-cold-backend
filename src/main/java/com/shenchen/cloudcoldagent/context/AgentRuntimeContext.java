package com.shenchen.cloudcoldagent.context;

import com.shenchen.cloudcoldagent.model.vo.agent.AgentStreamEvent;

/**
 * Agent 运行时上下文，供工具在执行期间读取当前用户、会话作用域以及多智能体事件发射器。
 */
public final class AgentRuntimeContext {

    private static final ThreadLocal<AgentExecutionContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private AgentRuntimeContext() {
    }

    /**
     * 多智能体事件发射器，供 Worker 在执行期间向前端推送实时事件。
     */
    @FunctionalInterface
    public interface MultiAgentEventEmitter {
        void emit(AgentStreamEvent event);
    }

    /**
     * 在当前线程绑定用户和会话上下文，返回可自动关闭的 Scope。
     */
    public static Scope open(Long userId, String conversationId) {
        return open(userId, conversationId, null);
    }

    /**
     * 在当前线程绑定用户、会话上下文和多智能体事件发射器，返回可自动关闭的 Scope。
     */
    public static Scope open(Long userId, String conversationId, MultiAgentEventEmitter emitter) {
        AgentExecutionContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(new AgentExecutionContext(userId, conversationId, emitter));
        return new Scope(previous);
    }

    public static Long getCurrentUserId() {
        AgentExecutionContext context = CONTEXT_HOLDER.get();
        return context == null ? null : context.userId();
    }

    public static String getCurrentConversationId() {
        AgentExecutionContext context = CONTEXT_HOLDER.get();
        return context == null ? null : context.conversationId();
    }

    public static MultiAgentEventEmitter getCurrentEmitter() {
        AgentExecutionContext context = CONTEXT_HOLDER.get();
        return context == null ? null : context.emitter();
    }

    /**
     * `Scope` 类型实现。
     */
    public static final class Scope implements AutoCloseable {

        private final AgentExecutionContext previous;

        private Scope(AgentExecutionContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CONTEXT_HOLDER.remove();
                return;
            }
            CONTEXT_HOLDER.set(previous);
        }
    }

    /**
     * `AgentExecutionContext` 记录对象。
     */
    private record AgentExecutionContext(Long userId, String conversationId, MultiAgentEventEmitter emitter) {
    }
}
