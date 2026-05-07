package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * 创建 `ResumeContext` 实例。
 *
 * @param question question 参数。
 * @param round round 参数。
 * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
 * @param messages messages 参数。
 * @param executedTasks executedTasks 参数。
 * @param approvedToolCallIds approvedToolCallIds 参数。
 * @param skillRuntimeContexts skillRuntimeContexts 参数。
 * @param currentPlan currentPlan 参数。
 * @param currentTask currentTask 参数。
 */
/**
 * `ResumeContext` 记录对象。
 */
public record ResumeContext(
        String question,
        int round,
        String runtimeSystemPrompt,
        List<Message> messages,
        Map<String, ExecutedTaskSnapshot> executedTasks,
        List<String> approvedToolCallIds,
        List<SkillRuntimeContext> skillRuntimeContexts,
        List<PlanTask> currentPlan,
        PlanTask currentTask
) {
}
