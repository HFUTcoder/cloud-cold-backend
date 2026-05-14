package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

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
