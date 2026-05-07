package com.shenchen.cloudcoldagent.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * `UserRoleEnum` 枚举定义。
 */
@Getter
public enum UserRoleEnum {

    USER("用户", "user"),
    ADMIN("管理员", "admin");

    private final String text;

    private final String value;

    /**
     * 创建 `UserRoleEnum` 实例。
     *
     * @param text text 参数。
     * @param value value 参数。
     */
    UserRoleEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取 `get Enum By Value` 对应结果。
     *
     * @param value value 参数。
     * @return 返回处理结果。
     */
    public static UserRoleEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (UserRoleEnum anEnum : UserRoleEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}