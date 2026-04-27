package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public record ResumeContext(
        String question,
        int round,
        String runtimeSystemPrompt,
        List<Message> messages,
        Map<String, ExecutedTaskSnapshot> executedTasks,
        List<String> approvedToolNames,
        List<PlanTask> currentPlan,
        PlanTask currentTask
) {
}
