package com.shenchen.cloudcoldagent.skillworkflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCandidateListResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<SkillCandidate> items;
}
