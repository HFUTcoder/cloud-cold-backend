package com.shenchen.cloudcoldagent.context;

/**
 * Agent 运行时上下文，供工具在执行期间读取当前用户与会话作用域。
 */
public final class AgentRuntimeContext {

    private static final ThreadLocal<AgentExecutionContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 创建 `AgentRuntimeContext` 实例。
     */
    private AgentRuntimeContext() {
    }

    /**
     * 处理 `open` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    public static Scope open(Long userId, String conversationId) {
        AgentExecutionContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(new AgentExecutionContext(userId, conversationId));
        return new Scope(previous);
    }

    /**
     * 获取 `get Current User Id` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static Long getCurrentUserId() {
        AgentExecutionContext context = CONTEXT_HOLDER.get();
        return context == null ? null : context.userId();
    }

    /**
     * 获取 `get Current Conversation Id` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getCurrentConversationId() {
        AgentExecutionContext context = CONTEXT_HOLDER.get();
        return context == null ? null : context.conversationId();
    }

    /**
     * `Scope` 类型实现。
     */
    public static final class Scope implements AutoCloseable {

        private final AgentExecutionContext previous;

        /**
         * 创建 `Scope` 实例。
         *
         * @param previous previous 参数。
         */
        private Scope(AgentExecutionContext previous) {
            this.previous = previous;
        }

        /**
         * 处理 `close` 对应逻辑。
         */
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
     * 创建 `AgentExecutionContext` 实例。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     */
    /**
     * `AgentExecutionContext` 记录对象。
     */
    private record AgentExecutionContext(Long userId, String conversationId) {
    }
}
