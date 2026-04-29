package com.shenchen.cloudcoldagent.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

@Getter
public enum AgentModeEnum {

    FAST("快速模式", "fast"),
    THINKING("思考模式", "thinking"),
    EXPERT("专家模式", "expert");

    private final String text;

    private final String value;

    AgentModeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AgentModeEnum fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }
}
