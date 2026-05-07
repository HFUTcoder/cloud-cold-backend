package com.shenchen.cloudcoldagent.workflow.skill.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * `SkillWorkflowResult` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillWorkflowResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> selectedSkills;

    private List<SkillRuntimeContext> selectedSkillContexts;
}
