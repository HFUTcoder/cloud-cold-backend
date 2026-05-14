package com.shenchen.cloudcoldagent.model.vo.skill;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Skill 资源读取结果视图。
 */
@Data
@Builder
public class SkillResourceContentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String skillName;

    /**
     * 资源类型：main/reference/script
     */
    private String resourceType;

    /**
     * 资源相对路径；main 类型为空。
     */
    private String resourcePath;

    /**
     * 实际读取的起始行号
     */
    private Integer startLine;

    /**
     * 实际读取的结束行号
     */
    private Integer endLine;

    /**
     * 是否被裁剪
     */
    private Boolean truncated;

    /**
     * 资源内容
     */
    private String content;
}
