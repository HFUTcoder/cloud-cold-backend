package com.shenchen.cloudcoldagent.exception;

import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;

/**
 * 表示 Agent 执行因 HITL（Human-in-the-Loop）中断而无法继续，需等待人工处理后恢复。
 */
public class HitlInterruptedException extends RuntimeException {

    private final transient AgentStreamEvent interruptEvent;

    public HitlInterruptedException(AgentStreamEvent interruptEvent) {
        super("Agent execution interrupted by HITL");
        this.interruptEvent = interruptEvent;
    }

    public AgentStreamEvent getInterruptEvent() {
        return interruptEvent;
    }
}
