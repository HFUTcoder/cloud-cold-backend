package com.shenchen.cloudcoldagent.model.entity.record.hitl;

/**
 * 创建 `PendingToolCall` 实例。
 *
 * @param id id 参数。
 * @param name name 参数。
 * @param arguments arguments 参数。
 * @param result result 参数。
 * @param description description 参数。
 */
/**
 * `PendingToolCall` 记录对象。
 */
public record PendingToolCall(String id, String name, String arguments, FeedbackResult result, String description) {

    /**
     * 创建 `FeedbackResult` 实例。
     */
    /**
     * `FeedbackResult` 枚举定义。
     */
    public enum FeedbackResult {
        APPROVED,
        REJECTED,
        EDIT
    }

    /**
     * 处理 `approve` 对应逻辑。
     *
     * @return 返回处理结果。
     */
    public PendingToolCall approve() {
        return new PendingToolCall(id, name, arguments, FeedbackResult.APPROVED, description);
    }

    /**
     * 处理 `reject` 对应逻辑。
     *
     * @param reason reason 参数。
     * @return 返回处理结果。
     */
    public PendingToolCall reject(String reason) {
        return new PendingToolCall(id, name, arguments, FeedbackResult.REJECTED, reason);
    }
}
