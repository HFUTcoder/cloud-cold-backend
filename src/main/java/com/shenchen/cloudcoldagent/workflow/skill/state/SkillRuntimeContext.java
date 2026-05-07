package com.shenchen.cloudcoldagent.workflow.skill.state;

import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * `SkillRuntimeContext` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRuntimeContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skillName;

    private String content;

    private SkillResourceListVO resourceList;

    private Boolean hasExecutableScript;

    private String singleScriptPath;

    private Map<String, SkillArgumentSpec> scriptArgumentSpecs;
}
