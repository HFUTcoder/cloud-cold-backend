package com.shenchen.cloudcoldagent.model.dto.agent;

import com.shenchen.cloudcoldagent.enums.AgentModeEnum;
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
    private AgentModeEnum mode;

    /**
     * 会话 id（可选，不传则使用默认会话）
     */
    private String conversationId;

}
