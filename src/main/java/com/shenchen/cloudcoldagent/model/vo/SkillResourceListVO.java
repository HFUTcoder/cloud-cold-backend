package com.shenchen.cloudcoldagent.model.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Skill 资源清单视图。
 */
@Data
@Builder
public class SkillResourceListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String skillName;

    /**
     * 主说明文件
     */
    private String mainFile;

    /**
     * reference 资源列表
     */
    private List<String> references;

    /**
     * script 资源列表
     */
    private List<String> scripts;
}
