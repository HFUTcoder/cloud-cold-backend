package com.shenchen.cloudcoldagent.model.vo.skill;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * Skill 脚本执行结果视图。
 */
@Data
@Builder
public class SkillScriptExecutionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String skillName;

    /**
     * 脚本相对路径
     */
    private String scriptPath;

    /**
     * 执行参数
     */
    private Map<String, Object> arguments;

    /**
     * 执行结果
     */
    private String result;

    /**
     * 执行引擎
     */
    private String engine;
}
