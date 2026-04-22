package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonArgumentUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonArgumentUtils() {
    }

    public static NormalizationResult normalizeJsonArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new NormalizationResult("{}", false, false);
        }
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(rawArguments);
            String normalized = jsonNode == null ? "{}" : OBJECT_MAPPER.writeValueAsString(jsonNode);
            return new NormalizationResult(normalized, false, false);
        } catch (Exception e) {
            String repairedArguments = tryRepairJsonArguments(rawArguments);
            if (repairedArguments != null) {
                return new NormalizationResult(repairedArguments, true, false);
            }
            return new NormalizationResult("{}", false, true);
        }
    }

    private static String tryRepairJsonArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return null;
        }
        String trimmed = rawArguments.trim();
        String candidate = appendMissingClosers(trimmed);
        if (candidate == null) {
            return null;
        }
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(candidate);
            return jsonNode == null ? null : OBJECT_MAPPER.writeValueAsString(jsonNode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String appendMissingClosers(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(text);
        int objectBalance = 0;
        int arrayBalance = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                objectBalance++;
            } else if (ch == '}') {
                objectBalance--;
            } else if (ch == '[') {
                arrayBalance++;
            } else if (ch == ']') {
                arrayBalance--;
            }
        }

        if (inString) {
            builder.append('"');
        }
        while (arrayBalance > 0) {
            builder.append(']');
            arrayBalance--;
        }
        while (objectBalance > 0) {
            builder.append('}');
            objectBalance--;
        }
        return builder.toString();
    }

    public record NormalizationResult(String normalizedJson, boolean repaired, boolean fallbackToEmptyObject) {
    }
}
