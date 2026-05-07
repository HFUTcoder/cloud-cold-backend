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

    /**
     * 创建 `HitlCheckpointStatusEnum` 实例。
     *
     * @param text text 参数。
     * @param value value 参数。
     */
    HitlCheckpointStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 处理 `from Value` 对应逻辑。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
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
