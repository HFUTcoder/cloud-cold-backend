package com.shenchen.cloudcoldagent.model.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Skill 详情视图。
 */
@Data
@Builder
public class SkillContentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String skillName;

    /**
     * skill 完整内容
     */
    private String content;
}
