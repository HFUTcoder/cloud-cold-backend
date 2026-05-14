package com.shenchen.cloudcoldagent.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * `HitlCheckpointStatusEnum` 枚举定义。
 */
@Getter
public enum HitlCheckpointStatusEnum {

    PENDING("待处理", "PENDING"),
    RESOLVED("已处理", "RESOLVED"),
    CONSUMED("已消费", "CONSUMED"),
    CANCELLED("已取消", "CANCELLED");

    private final String text;

    private final String value;

    HitlCheckpointStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    public static HitlCheckpointStatusEnum fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }
}
