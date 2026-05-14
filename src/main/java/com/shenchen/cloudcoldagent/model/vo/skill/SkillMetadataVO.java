package com.shenchen.cloudcoldagent.model.vo.skill;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Skill 轻量元数据视图，仅用于渐进式披露阶段。
 */
@Data
@Builder
public class SkillMetadataVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * skill 名称
     */
    private String name;

    /**
     * skill 简要说明
     */
    private String description;

    /**
     * skill 来源
     */
    private String source;
}
