package com.shenchen.cloudcoldagent.workflow.skill.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * `SkillExecutionPlan` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skillName;

    private Boolean selected;

    private Boolean executable;

    private SkillToolCallPlan toolCallPlan;

    private String blockingReason;

    private String blockingUserMessage;
}
