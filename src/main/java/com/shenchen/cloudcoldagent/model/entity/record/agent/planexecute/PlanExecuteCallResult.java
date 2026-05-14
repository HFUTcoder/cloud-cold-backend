package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import com.shenchen.cloudcoldagent.model.vo.agent.AgentStreamEvent;

/**
 * plan-execute 同步调用结果，区分正常回答和 HITL 中断。
 */
public record PlanExecuteCallResult(
        String answer,
        AgentStreamEvent hitlInterruptEvent
) {
    public boolean interrupted() {
        return hitlInterruptEvent != null;
    }
}
