package com.shenchen.cloudcoldagent.workflow.skill.state;

import java.util.Set;

/**
 * Skill 参数类型枚举，通过别名匹配类型名并解析默认值。
 */
public enum SkillArgumentType {

    NUMBER(Set.of("number", "integer", "float", "double")) {
        @Override
        public Object parseValue(String rawValue) {
            String trimmed = rawValue.trim();
            return trimmed.contains(".") ? Double.parseDouble(trimmed) : Long.parseLong(trimmed);
        }
    },

    BOOLEAN(Set.of("boolean")) {
        @Override
        public Object parseValue(String rawValue) {
            return Boolean.parseBoolean(rawValue.trim());
        }
    },

    STRING(Set.of("string")) {
        @Override
        public Object parseValue(String rawValue) {
            return rawValue.trim();
        }
    };

    private final Set<String> aliases;

    SkillArgumentType(Set<String> aliases) {
        this.aliases = aliases;
    }

    /**
     * 根据类型名称匹配对应的枚举值，未匹配时默认返回 STRING。
     *
     * @param typeName 类型名称（大小写不敏感）。
     * @return 匹配的枚举值。
     */
    public static SkillArgumentType fromTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return STRING;
        }
        String normalized = typeName.trim().toLowerCase();
        for (SkillArgumentType type : values()) {
            if (type.aliases.contains(normalized)) {
                return type;
            }
        }
        return STRING;
    }

    /**
     * 将原始默认值文本按当前类型解析为对应的 Java 对象。
     *
     * @param rawValue 原始默认值文本。
     * @return 解析后的对象。
     */
    public abstract Object parseValue(String rawValue);
}
