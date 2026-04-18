package com.shenchen.cloudcoldagent.model.dto.agent;

import lombok.Data;

import java.io.Serializable;

/**
 * 智能体调用请求
 */
@Data
public class AgentCallRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户问题
     */
    private String question;

    /**
     * 工作模式
     */
    private String mode;

}