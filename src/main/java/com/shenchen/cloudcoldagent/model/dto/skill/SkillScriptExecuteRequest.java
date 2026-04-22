package com.shenchen.cloudcoldagent.model.dto.skill;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 执行 skill 脚本请求。
 */
@Data
public class SkillScriptExecuteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String skillName;

    /**
     * scripts 下的相对路径，例如 scripts/example.py
     */
    private String scriptPath;

    /**
     * 结构化参数
     */
    private Map<String, Object> arguments;
}
