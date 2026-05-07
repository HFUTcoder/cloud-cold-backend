package com.shenchen.cloudcoldagent.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * `DocumentIndexStatusEnum` 枚举定义。
 */
@Getter
public enum DocumentIndexStatusEnum {

    PENDING("待处理", "PENDING"),
    INDEXING("索引中", "INDEXING"),
    INDEXED("已完成", "INDEXED"),
    FAILED("失败", "FAILED");

    private final String text;

    private final String value;

    /**
     * 创建 `DocumentIndexStatusEnum` 实例。
     *
     * @param text text 参数。
     * @param value value 参数。
     */
    DocumentIndexStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取 `get Enum By Value` 对应结果。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    public static DocumentIndexStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (DocumentIndexStatusEnum anEnum : DocumentIndexStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
