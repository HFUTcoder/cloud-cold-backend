package com.shenchen.cloudcoldagent.skillworkflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillScriptExecutionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skillName;

    private String scriptPath;

    private Map<String, SkillArgumentSpec> argumentSpecs;

    private Map<String, Object> arguments;
}
