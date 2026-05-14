package com.shenchen.cloudcoldagent.model.entity.record.hitl;

/**
 * `PendingToolCall` 记录对象。
 */
public record PendingToolCall(String id, String name, String arguments, FeedbackResult result, String description) {

    /**
     * `FeedbackResult` 枚举定义。
     */
    public enum FeedbackResult {
        APPROVED,
        REJECTED,
        EDIT
    }

    public PendingToolCall approve() {
        return new PendingToolCall(id, name, arguments, FeedbackResult.APPROVED, description);
    }

    /**
     * @param reason 拒绝原因，会覆盖原 description
     */
    public PendingToolCall reject(String reason) {
        return new PendingToolCall(id, name, arguments, FeedbackResult.REJECTED, reason);
    }
}
