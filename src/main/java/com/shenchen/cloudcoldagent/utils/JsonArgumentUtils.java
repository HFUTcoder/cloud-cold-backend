package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;

public final class JsonArgumentUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

}
