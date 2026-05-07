package com.shenchen.cloudcoldagent.workflow.skill.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * `SkillArgumentSpec` 类型实现。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillArgumentSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String displayName;

    private String type;

    private Boolean required;

    private Boolean optional;

    private Object defaultValue;
}
