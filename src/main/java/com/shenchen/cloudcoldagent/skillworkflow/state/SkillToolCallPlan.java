package com.shenchen.cloudcoldagent.skillworkflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillToolCallPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    private String toolName;

    private SkillScriptExecutionRequest request;

    private String summary;
}
