package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlanExecuteResumeUtils {

    public static final String CONTEXT_KEY = "planExecuteResumeContext";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PlanExecuteResumeUtils() {
    }

    public static Map<String, Object> buildContext(PlanExecuteAgent.OverAllState state,
                                                   List<PlanExecuteAgent.PlanTask> currentPlan,
                                                   PlanExecuteAgent.PlanTask currentTask,
                                                   String runtimeSystemPrompt) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", state == null ? null : state.getQuestion());
        payload.put("round", state == null ? 0 : state.getRound());
        payload.put("runtimeSystemPrompt", runtimeSystemPrompt);
        payload.put("messagesJson", HitlSerializationUtils.writeMessages(state == null ? List.of() : state.getMessages()));
        payload.put("executedTasksJson", HitlSerializationUtils.writeJson(state == null ? Map.of() : state.getExecutedTasks()));
        payload.put("currentPlanJson", HitlSerializationUtils.writeJson(currentPlan == null ? List.of() : currentPlan));
        payload.put("currentTaskJson", HitlSerializationUtils.writeJson(currentTask));
        context.put(CONTEXT_KEY, payload);
        return context;
    }

    public static ResumeContext readContext(Map<String, Object> context) {
        if (context == null || !(context.get(CONTEXT_KEY) instanceof Map<?, ?> payload)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 checkpoint 不包含 PlanExecute resume 上下文");
        }
        String payloadJson = HitlSerializationUtils.writeJson(payload);
        try {
            Map<String, Object> normalized = OBJECT_MAPPER.readValue(payloadJson, new TypeReference<>() {
            });
            String question = stringValue(normalized.get("question"));
            int round = intValue(normalized.get("round"));
            String runtimeSystemPrompt = stringValue(normalized.get("runtimeSystemPrompt"));
            String messagesJson = stringValue(normalized.get("messagesJson"));
            String executedTasksJson = stringValue(normalized.get("executedTasksJson"));
            String currentPlanJson = stringValue(normalized.get("currentPlanJson"));
            String currentTaskJson = stringValue(normalized.get("currentTaskJson"));

            List<Message> messages = HitlSerializationUtils.readMessages(messagesJson);
            Map<String, PlanExecuteAgent.ExecutedTaskSnapshot> executedTasks = executedTasksJson == null || executedTasksJson.isBlank()
                    ? new LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(executedTasksJson, new TypeReference<>() {
                    });
            List<PlanExecuteAgent.PlanTask> currentPlan = currentPlanJson == null || currentPlanJson.isBlank()
                    ? List.of()
                    : OBJECT_MAPPER.readValue(currentPlanJson, new TypeReference<>() {
                    });
            PlanExecuteAgent.PlanTask currentTask = currentTaskJson == null || currentTaskJson.isBlank() || "null".equals(currentTaskJson)
                    ? null
                    : OBJECT_MAPPER.readValue(currentTaskJson, PlanExecuteAgent.PlanTask.class);

            return new ResumeContext(question, round, runtimeSystemPrompt, messages, executedTasks, currentPlan, currentTask);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PlanExecute resume 上下文反序列化失败");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public record ResumeContext(
            String question,
            int round,
            String runtimeSystemPrompt,
            List<Message> messages,
            Map<String, PlanExecuteAgent.ExecutedTaskSnapshot> executedTasks,
            List<PlanExecuteAgent.PlanTask> currentPlan,
            PlanExecuteAgent.PlanTask currentTask
    ) {
    }
}
