package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 修复工具类
 * 用于处理大模型返回的可能包含错误的 JSON 字符串
 *
 * <p>修复 Pipeline：markdown 提取 → 提取平衡 JSON → 引号修复 → 尾部逗号修复 → 缺失引号修复 → 转义修复 → 验证</p>
 */
public final class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonUtil() {
    }

    /**
     * 修复 JSON 字符串，返回修复后的文本。
     *
     * @param jsonString 可能包含错误的 JSON 字符串
     * @return 修复后的 JSON 字符串，如果输入为空则返回 "{}"
     */
    public static String fixJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return "{}";
        }

        String fixed = jsonString.trim();

        // 1. 提取 JSON 内容（移除 markdown 代码块标记）
        fixed = extractJsonFromMarkdown(fixed);

        // 2. 提取平衡的 JSON 对象/数组（移除前后垃圾字符）
        fixed = extractBalancedJson(fixed);

        // 3. 修复常见的引号问题
        fixed = fixQuotes(fixed);

        // 4. 修复尾部逗号问题
        fixed = fixTrailingCommas(fixed);

        // 5. 修复缺失的引号
        fixed = fixMissingQuotes(fixed);

        // 6. 修复转义字符问题
        fixed = fixEscapeChars(fixed);

        // 7. 尝试验证
        try {
            OBJECT_MAPPER.readTree(fixed);
            return fixed;
        } catch (Exception e) {
            log.warn("JSON 修复后仍然无效，尝试包装为简单对象。Error: {}", e.getMessage());
            return wrapAsSimpleJson(jsonString);
        }
    }

    /**
     * 修复并解析为 JsonNode。
     *
     * @param jsonString JSON 字符串
     * @return JsonNode 对象，解析失败时返回 Empty ObjectNode
     */
    public static JsonNode fixAndParse(String jsonString) {
        String fixed = fixJson(jsonString);
        try {
            return OBJECT_MAPPER.readTree(fixed);
        } catch (Exception e) {
            log.error("JSON 解析失败", e);
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /**
     * 修复并解析为指定类型。
     *
     * @param jsonString JSON 字符串
     * @param valueType  目标类型
     * @return 解析后的对象
     * @throws JsonProcessingException 解析失败时抛出
     */
    public static <T> T fixAndParse(String jsonString, Class<T> valueType) throws JsonProcessingException {
        String fixed = fixJson(jsonString);
        return OBJECT_MAPPER.readValue(fixed, valueType);
    }

    /**
     * 验证 JSON 字符串是否有效。
     *
     * @param jsonString JSON 字符串
     * @return true 如果有效
     */
    public static boolean isValidJson(String jsonString) {
        try {
            OBJECT_MAPPER.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 美化 JSON 字符串。
     *
     * @param jsonString JSON 字符串
     * @return 格式化后的 JSON 字符串
     */
    public static String prettify(String jsonString) {
        try {
            Object json = OBJECT_MAPPER.readValue(jsonString, Object.class);
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            log.error("JSON 美化失败", e);
            return jsonString;
        }
    }

    /**
     * ObjectMapper 实例（供需要原始 ObjectMapper 的场景使用）。
     */
    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    // ---- 修复步骤 ----

    /**
     * 从 Markdown 代码块中提取 JSON。
     */
    private static String extractJsonFromMarkdown(String text) {
        Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text;
    }

    /**
     * 提取平衡的 JSON 对象或数组。
     * 从第一个 '{' 或 '[' 开始，追踪字符串状态和转义字符，找到匹配的闭合位置。
     */
    private static String extractBalancedJson(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return text;
        }

        char opening = text.charAt(start);
        char closing = opening == '{' ? '}' : (opening == '[' ? ']' : '\0');
        if (closing == '\0') {
            return text;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == opening) {
                depth++;
                continue;
            }
            if (ch == closing) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        return text.substring(start);
    }

    /**
     * 修复引号问题（中文引号、单引号）。
     */
    private static String fixQuotes(String text) {
        text = text.replace('“', '"').replace('”', '"');
        text = text.replace('‘', '\'').replace('’', '\'');

        return text;
    }

    /**
     * 修复 JSON 对象和数组中的尾部逗号。
     */
    private static String fixTrailingCommas(String text) {
        text = text.replaceAll(",\\s*}", "}");
        text = text.replaceAll(",\\s*]", "]");
        return text;
    }

    /**
     * 修复 JSON key 缺失的引号。
     */
    private static String fixMissingQuotes(String text) {
        text = text.replaceAll("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "$1\"$2\":");
        return text;
    }

    /**
     * 修复非法转义字符。
     * 将 JSON 字符串值中可能出现的原始控制字符替换为空格。
     */
    private static String fixEscapeChars(String text) {
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        return text;
    }

    /**
     * 将无法修复的文本包装为简单 JSON 对象，作为最后的兜底。
     */
    private static String wrapAsSimpleJson(String text) {
        try {
            String escaped = text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            return "{\"content\":\"" + escaped + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Invalid JSON\"}";
        }
    }
}
