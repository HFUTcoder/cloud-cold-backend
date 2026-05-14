package com.shenchen.cloudcoldagent.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * Worker 任务状态枚举。
 */
@Getter
public enum WorkerTaskStatusEnum {

    PENDING("待执行", "PENDING"),
    RUNNING("执行中", "RUNNING"),
    COMPLETED("已完成", "COMPLETED"),
    FAILED("执行失败", "FAILED");

    private final String text;

    private final String value;

    WorkerTaskStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    public static WorkerTaskStatusEnum fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }
}
