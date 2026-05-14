package com.shenchen.cloudcoldagent.context;

/**
 * Agent 运行时上下文，供工具在执行期间读取当前用户与会话作用域。
 */
public final class AgentRuntimeContext {

    private static final ThreadLocal<AgentExecutionContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private AgentRuntimeContext() {
    }

    /**
     * 在当前线程绑定用户和会话上下文，返回可自动关闭的 Scope。
     */
    public static Scope open(Long userId, String conversationId) {
        AgentExecutionContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(new AgentExecutionContext(userId, conversationId));
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
    private record AgentExecutionContext(Long userId, String conversationId) {
    }
}
