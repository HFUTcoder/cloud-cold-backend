package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ExecutedTaskSnapshot;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanTask;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.ResumeContext;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * `PlanExecuteResumeUtils` 类型实现。
 */
public final class PlanExecuteResumeUtils {

    public static final String CONTEXT_KEY = "planExecuteResumeContext";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 创建 `PlanExecuteResumeUtils` 实例。
     */
    private PlanExecuteResumeUtils() {
    }

    /**
     * 构建 `build Context` 对应结果。
     *
     * @param state state 参数。
     * @param currentPlan currentPlan 参数。
     * @param currentTask currentTask 参数。
     * @param runtimeSystemPrompt runtimeSystemPrompt 参数。
     * @return 返回处理结果。
     */
    public static Map<String, Object> buildContext(PlanExecuteAgent.OverAllState state,
                                                   List<PlanTask> currentPlan,
                                                   PlanTask currentTask,
                                                   String runtimeSystemPrompt) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", state == null ? null : state.getQuestion());
        payload.put("round", state == null ? 0 : state.getRound());
        payload.put("runtimeSystemPrompt", runtimeSystemPrompt);
        payload.put("messagesJson", HitlSerializationUtils.writeMessages(state == null ? List.of() : state.getMessages()));
        payload.put("executedTasksJson", HitlSerializationUtils.writeJson(normalizeExecutedTasks(
                state == null ? Map.of() : state.getExecutedTasks()
        )));
        payload.put("approvedToolCallIdsJson", HitlSerializationUtils.writeJson(state == null ? List.of() : state.getApprovedToolCallIds()));
        payload.put("skillRuntimeContextsJson", HitlSerializationUtils.writeJson(
                state == null ? List.of() : state.getSkillRuntimeContexts()
        ));
        payload.put("currentPlanJson", HitlSerializationUtils.writeJson(normalizePlan(currentPlan)));
        payload.put("currentTaskJson", HitlSerializationUtils.writeJson(normalizeTask(currentTask)));
        context.put(CONTEXT_KEY, payload);
        return context;
    }

    private static Map<String, Object> normalizeExecutedTasks(Map<String, ExecutedTaskSnapshot> executedTasks) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (executedTasks == null || executedTasks.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, ExecutedTaskSnapshot> entry : executedTasks.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            ExecutedTaskSnapshot snapshot = entry.getValue();
            Map<String, Object> snapshotMap = new LinkedHashMap<>();
            snapshotMap.put("taskId", snapshot.taskId());
            snapshotMap.put("toolName", snapshot.toolName());
            snapshotMap.put("arguments", normalizeArguments(snapshot.arguments()));
            snapshotMap.put("summary", snapshot.summary());
            snapshotMap.put("success", snapshot.success());
            snapshotMap.put("output", snapshot.output());
            snapshotMap.put("error", snapshot.error());
            snapshotMap.put("round", snapshot.round());
            normalized.put(entry.getKey(), snapshotMap);
        }
        return normalized;
    }

    private static List<Map<String, Object>> normalizePlan(List<PlanTask> currentPlan) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (currentPlan == null || currentPlan.isEmpty()) {
            return normalized;
        }
        for (PlanTask task : currentPlan) {
            Map<String, Object> taskMap = normalizeTask(task);
            if (taskMap != null) {
                normalized.add(taskMap);
            }
        }
        return normalized;
    }

    private static Map<String, Object> normalizeTask(PlanTask task) {
        if (task == null) {
            return null;
        }
        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.id());
        taskMap.put("toolName", task.toolName());
        taskMap.put("arguments", normalizeArguments(task.arguments()));
        taskMap.put("order", task.order());
        taskMap.put("summary", task.summary());
        return taskMap;
    }

    private static Map<String, Object> normalizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            normalized.put(entry.getKey(), normalizeJsonFriendlyValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeJsonFriendlyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), normalizeJsonFriendlyValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof List<?> listValue) {
            List<Object> normalized = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                normalized.add(normalizeJsonFriendlyValue(item));
            }
            return normalized;
        }
        if (value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeJsonFriendlyValue(java.lang.reflect.Array.get(value, i)));
            }
            return normalized;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return String.valueOf(value);
    }

    /**
     * 处理 `read Context` 对应逻辑。
     *
     * @param context context 参数。
     * @return 返回处理结果。
     */
    public static ResumeContext readContext(Map<String, Object> context) {
        if (context == null || !(context.get(CONTEXT_KEY) instanceof Map<?, ?> payload)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前 checkpoint 不包含 PlanExecute resume 上下文");
        }
        String payloadJson = HitlSerializationUtils.writeJson(payload);
        try {
            /**
             * `` 类型实现。
             */
            Map<String, Object> normalized = OBJECT_MAPPER.readValue(payloadJson, new TypeReference<>() {
            });
            String question = stringValue(normalized.get("question"));
            int round = intValue(normalized.get("round"));
            String runtimeSystemPrompt = stringValue(normalized.get("runtimeSystemPrompt"));
            String messagesJson = stringValue(normalized.get("messagesJson"));
            String executedTasksJson = stringValue(normalized.get("executedTasksJson"));
            String approvedToolCallIdsJson = stringValue(normalized.get("approvedToolCallIdsJson"));
            String skillRuntimeContextsJson = stringValue(normalized.get("skillRuntimeContextsJson"));
            String currentPlanJson = stringValue(normalized.get("currentPlanJson"));
            String currentTaskJson = stringValue(normalized.get("currentTaskJson"));

            List<Message> messages = HitlSerializationUtils.readMessages(messagesJson);
            Map<String, ExecutedTaskSnapshot> executedTasks = executedTasksJson == null || executedTasksJson.isBlank()
                    ? new LinkedHashMap<>()
                    /**
                     * `` 类型实现。
                     */
                    : OBJECT_MAPPER.readValue(executedTasksJson, new TypeReference<>() {
                    });
            List<String> approvedToolCallIds = approvedToolCallIdsJson == null || approvedToolCallIdsJson.isBlank()
                    ? List.of()
                    /**
                     * `` 类型实现。
                     */
                    : OBJECT_MAPPER.readValue(approvedToolCallIdsJson, new TypeReference<>() {
                    });
            List<SkillRuntimeContext> skillRuntimeContexts = skillRuntimeContextsJson == null || skillRuntimeContextsJson.isBlank()
                    ? List.of()
                    : OBJECT_MAPPER.readValue(skillRuntimeContextsJson, new TypeReference<>() {
                    });
            List<PlanTask> currentPlan = currentPlanJson == null || currentPlanJson.isBlank()
                    ? List.of()
                    /**
                     * `` 类型实现。
                     */
                    : OBJECT_MAPPER.readValue(currentPlanJson, new TypeReference<>() {
                    });
            PlanTask currentTask = currentTaskJson == null || currentTaskJson.isBlank() || "null".equals(currentTaskJson)
                    ? null
                    : OBJECT_MAPPER.readValue(currentTaskJson, PlanTask.class);

            return new ResumeContext(question, round, runtimeSystemPrompt, messages, executedTasks, approvedToolCallIds, skillRuntimeContexts, currentPlan, currentTask);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PlanExecute resume 上下文反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 处理 `string Value` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 处理 `int Value` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
