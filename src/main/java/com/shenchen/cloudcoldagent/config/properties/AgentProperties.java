package com.shenchen.cloudcoldagent.config.properties;

import com.shenchen.cloudcoldagent.prompts.ReactAgentPrompts;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * `AgentProperties` 类型实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.agent")
public class AgentProperties {

    private Memory memory = new Memory();

    private React react = new React();

    private Plan plan = new Plan();

    /**
     * `Memory` 类型实现。
     */
    @Data
    public static class Memory {

        /**
         * 每个会话保留的最大消息数。
         */
        private int maxMessages = 20;
    }

    /**
     * `React` 类型实现。
     */
    @Data
    public static class React {

        private String name = "ReactAgent";

        private int maxRounds = 5;

        private String systemPrompt = ReactAgentPrompts.DEFAULT_REACT_SYSTEM_PROMPT;
    }

    /**
     * `Plan` 类型实现。
     */
    @Data
    public static class Plan {

        private String agentType = "PlanExecuteAgent";

        private int maxRounds = 5;

        private int maxToolRetries = 5;

        private int contextCharLimit = 5000;

        private int toolConcurrency = 3;
    }
}
