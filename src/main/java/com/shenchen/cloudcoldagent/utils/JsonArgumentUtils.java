package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonArgumentUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String EXECUTE_SKILL_SCRIPT_TOOL = "execute_skill_script";

    private JsonArgumentUtils() {
    }

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
            normalizedArguments.put("arguments", businessArguments);
            return normalizedArguments;
        }

        copyIfPresent(template, normalizedArguments, "skillName");
        copyIfPresent(template, normalizedArguments, "scriptPath");
        copyIfPresent(template, normalizedArguments, "argumentSpecs");

        Object currentArguments = normalizedArguments.get("arguments");
        if (!(currentArguments instanceof Map<?, ?>)) {
            Object templateArgumentsValue = template.get("arguments");
            if (templateArgumentsValue instanceof Map<?, ?> templateMap) {
                normalizedArguments.put("arguments", new LinkedHashMap<>((Map<String, Object>) templateMap));
            }
        }
        return normalizedArguments;
    }

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
