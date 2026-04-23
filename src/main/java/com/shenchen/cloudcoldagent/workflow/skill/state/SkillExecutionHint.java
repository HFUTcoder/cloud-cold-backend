package com.shenchen.cloudcoldagent.workflow.skill.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionHint implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skillName;

    private String source;

    private Boolean shouldUse;

    private String reason;

    private Boolean shouldExecuteScript;

    private String preferredScriptPath;

    private Map<String, Object> suggestedArguments;

    private List<String> requiredReferences;

    private String conciseInstruction;
}
