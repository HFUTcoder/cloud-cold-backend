package com.shenchen.cloudcoldagent.model.dto.agent;

import lombok.Data;

import java.io.Serializable;

/**
 * `AgentResumeRequest` 类型实现。
 */
@Data
public class AgentResumeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String interruptId;
}
