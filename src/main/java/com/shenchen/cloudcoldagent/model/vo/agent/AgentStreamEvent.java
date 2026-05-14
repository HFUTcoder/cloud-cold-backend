package com.shenchen.cloudcoldagent.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * `AgentStreamEvent` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;

    private String conversationId;

    private String interruptId;

    private AgentStreamEventData data;
}
