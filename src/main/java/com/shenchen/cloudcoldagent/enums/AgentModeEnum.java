package com.shenchen.cloudcoldagent.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

/**
 * `AgentModeEnum` 枚举定义。
 */
@Getter
public enum AgentModeEnum {

    FAST("快速模式", "fast"),
    THINKING("思考模式", "thinking"),
    EXPERT("专家模式", "expert");

    private final String text;

    private final String value;

    /**
     * 创建 `AgentModeEnum` 实例。
     *
     * @param text text 参数。
     * @param value value 参数。
     */
    AgentModeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取 `get Value` 对应结果。
     *
     * @return 返回处理结果。
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 处理 `from Value` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
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
