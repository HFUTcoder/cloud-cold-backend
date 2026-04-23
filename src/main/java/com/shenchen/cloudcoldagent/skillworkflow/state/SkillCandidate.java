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
public class SkillCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skillName;

    private String source;

    private Boolean relevant;

    private String reason;

    private Double score;
}
