package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `JsonArgumentUtils` 类型实现。
 */
public final class JsonArgumentUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String EXECUTE_SKILL_SCRIPT_TOOL = "execute_skill_script";

    /**
     * 创建 `JsonArgumentUtils` 实例。
     */
    private JsonArgumentUtils() {
    }

    /**
     * 处理 `normalize Json Arguments` 对应逻辑。
     *
     * @param rawArguments rawArguments 参数。
     * @return 返回处理结果。
     */
    public static NormalizationResult normalizeJsonArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new NormalizationResult("{}", true, null);
        }
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(rawArguments);
            String normalized = jsonNode == null ? "{}" : OBJECT_MAPPER.writeValueAsString(jsonNode);
            return new NormalizationResult(normalized, true, null);
        } catch (Exception e) {
            return new NormalizationResult(rawArguments, false, e.getMessage());
        }
    }

    /**
     * 处理 `read Object Map` 对应逻辑。
     *
     * @param rawArguments rawArguments 参数。
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObjectMap(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(rawArguments);
            if (jsonNode == null || jsonNode.isNull() || !jsonNode.isObject()) {
                return new LinkedHashMap<>();
            }
            return OBJECT_MAPPER.convertValue(jsonNode, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 处理 `repair Structured Tool Arguments` 对应逻辑。
     *
     * @param toolName toolName 参数。
     * @param rawArguments rawArguments 参数。
     * @param templateArguments templateArguments 参数。
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> repairStructuredToolArguments(String toolName,
                                                                    Map<String, Object> rawArguments,
                                                                    Map<String, Object> templateArguments) {
        Map<String, Object> normalizedArguments = rawArguments == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(rawArguments);
        if (!EXECUTE_SKILL_SCRIPT_TOOL.equals(toolName)) {
            return normalizedArguments;
        }

        Map<String, Object> template = templateArguments == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(templateArguments);
        if (template.isEmpty()) {
            return normalizedArguments;
        }

        boolean hasOuterStructure = normalizedArguments.containsKey("skillName")
                || normalizedArguments.containsKey("scriptPath")
                || normalizedArguments.containsKey("arguments");

        if (!hasOuterStructure && !normalizedArguments.isEmpty()) {
            Map<String, Object> businessArguments = new LinkedHashMap<>(normalizedArguments);
            normalizedArguments.clear();
            copyIfPresent(template, normalizedArguments, "skillName");
            copyIfPresent(template, normalizedArguments, "scriptPath");
            copyIfPresent(template, normalizedArguments, "argumentSpecs");
            mergeTemplateArgumentsIntoBusiness(template, businessArguments);
            normalizedArguments.put("arguments", businessArguments);
            return normalizedArguments;
        }

        copyIfPresent(template, normalizedArguments, "skillName");
        copyIfPresent(template, normalizedArguments, "scriptPath");
        copyIfPresent(template, normalizedArguments, "argumentSpecs");

        mergeTemplateArguments(template, normalizedArguments);
        return normalizedArguments;
    }

    @SuppressWarnings("unchecked")
    private static void mergeTemplateArguments(Map<String, Object> template, Map<String, Object> target) {
        Object templateArgumentsValue = template.get("arguments");
        Map<String, Object> templateArgs = templateArgumentsValue instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();

        Object currentArguments = target.get("arguments");
        if (currentArguments instanceof Map<?, ?> currentMap) {
            for (Map.Entry<String, Object> entry : templateArgs.entrySet()) {
                if (!currentMap.containsKey(entry.getKey())) {
                    ((Map<String, Object>) currentArguments).put(entry.getKey(), entry.getValue());
                }
            }
        } else if (!templateArgs.isEmpty()) {
            target.put("arguments", templateArgs);
        } else if (!(currentArguments instanceof Map<?, ?>)) {
            target.put("arguments", new LinkedHashMap<>());
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeTemplateArgumentsIntoBusiness(Map<String, Object> template, Map<String, Object> businessArguments) {
        Object templateArgumentsValue = template.get("arguments");
        if (!(templateArgumentsValue instanceof Map<?, ?> templateMap)) {
            return;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) templateMap).entrySet()) {
            if (!businessArguments.containsKey(entry.getKey())) {
                businessArguments.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 校验 `validate Structured Tool Arguments` 对应内容。
     *
     * @param toolName toolName 参数。
     * @param arguments arguments 参数。
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    public static String validateStructuredToolArguments(String toolName, Map<String, Object> arguments) {
        if (!EXECUTE_SKILL_SCRIPT_TOOL.equals(toolName)) {
            return null;
        }
        if (arguments == null || arguments.isEmpty()) {
            return "execute_skill_script 缺少结构化 arguments，必须包含 skillName、scriptPath、arguments";
        }

        Object skillName = arguments.get("skillName");
        if (!(skillName instanceof String skillNameText) || skillNameText.isBlank()) {
            return "execute_skill_script 缺少 skillName";
        }

        Object scriptPath = arguments.get("scriptPath");
        if (!(scriptPath instanceof String scriptPathText) || scriptPathText.isBlank()) {
            return "execute_skill_script 缺少 scriptPath";
        }

        Object nestedArguments = arguments.get("arguments");
        if (!(nestedArguments instanceof Map<?, ?>)) {
            return "execute_skill_script 缺少 arguments 对象";
        }

        return null;
    }

    /**
     * 处理 `copy If Present` 对应逻辑。
     *
     * @param source source 参数。
     * @param target target 参数。
     * @param key key 参数。
     */
    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (target.containsKey(key)) {
            Object value = target.get(key);
            if (value instanceof String textValue && !textValue.isBlank()) {
                return;
            }
            if (value != null) {
                return;
            }
        }
        Object sourceValue = source.get(key);
        if (sourceValue != null) {
            target.put(key, sourceValue);
        }
    }

}
