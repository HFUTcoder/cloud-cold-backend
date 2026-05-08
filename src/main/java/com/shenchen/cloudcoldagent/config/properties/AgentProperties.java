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

    private ThreadPool threadPool = new ThreadPool();

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

        private int toolConcurrency = 3;

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

    @Data
    public static class ThreadPool {

        private Tool tool = new Tool();

        private Ltm ltm = new Ltm();

        @Data
        public static class Tool {
            private int corePoolSize = Runtime.getRuntime().availableProcessors();
            private int maxPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            private int queueCapacity = 100;
        }

        @Data
        public static class Ltm {
            private int corePoolSize = 1;
            private int maxPoolSize = 4;
            private int queueCapacity = 100;
        }
    }
}
