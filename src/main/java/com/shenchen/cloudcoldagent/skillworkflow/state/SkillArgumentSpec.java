package com.shenchen.cloudcoldagent.skillworkflow.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillArgumentSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String type;

    private Boolean required;

    private Boolean optional;

    private Object defaultValue;
}
