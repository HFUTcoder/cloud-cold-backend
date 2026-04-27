package com.shenchen.cloudcoldagent.model.entity.record.hitl;

public record PendingToolCall(String id, String name, String arguments, FeedbackResult result, String description) {

    public enum FeedbackResult {
        APPROVED,
        REJECTED,
        EDIT
    }

    public PendingToolCall approve() {
        return new PendingToolCall(id, name, arguments, FeedbackResult.APPROVED, description);
    }

    public PendingToolCall reject(String reason) {
        return new PendingToolCall(id, name, arguments, FeedbackResult.REJECTED, reason);
    }
}
