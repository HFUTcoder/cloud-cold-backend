package com.shenchen.cloudcoldagent.config.properties;

import com.shenchen.cloudcoldagent.constant.AgentConstant;
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

    private String timezone = AgentConstant.DEFAULT_TIMEZONE;

    private Memory memory = new Memory();

    private React react = new React();

    private Plan plan = new Plan();

    private Coordinator coordinator = new Coordinator();

    private ThreadPool threadPool = new ThreadPool();

    /**
     * `Memory` 类型实现。
     */
    @Data
    public static class Memory {

        /**
         * 每个会话保留的最大消息数。
         */
        private int maxMessages = AgentConstant.DEFAULT_MEMORY_MAX_MESSAGES;
    }

    /**
     * `React` 类型实现。
     */
    @Data
    public static class React {

        private String name = AgentConstant.DEFAULT_REACT_NAME;

        private int maxRounds = AgentConstant.DEFAULT_REACT_MAX_ROUNDS;

        private int toolConcurrency = AgentConstant.DEFAULT_REACT_TOOL_CONCURRENCY;

        private String systemPrompt = ReactAgentPrompts.DEFAULT_REACT_SYSTEM_PROMPT;
    }

    /**
     * `Plan` 类型实现。
     */
    @Data
    public static class Plan {

        private String name = AgentConstant.DEFAULT_PLAN_NAME;

        private String agentType = AgentConstant.DEFAULT_PLAN_AGENT_TYPE;

        private String systemPrompt;

        private int maxRounds = AgentConstant.DEFAULT_PLAN_MAX_ROUNDS;

        private int maxToolRetries = AgentConstant.DEFAULT_PLAN_MAX_TOOL_RETRIES;

        private int contextCharLimit = AgentConstant.DEFAULT_PLAN_CONTEXT_CHAR_LIMIT;

        private int toolConcurrency = AgentConstant.DEFAULT_PLAN_TOOL_CONCURRENCY;

        /**
         * 批量工具调用超时时间（秒）。
         */
        private long toolBatchTimeoutSeconds = AgentConstant.DEFAULT_PLAN_TOOL_BATCH_TIMEOUT_SECONDS;
    }

    /**
     * Coordinator 模式配置。
     */
    @Data
    public static class Coordinator {

        private String name = "CoordinatorAgent";

        private int maxRounds = 5;

        private int contextCharLimit = 10000;

        private int toolConcurrency = 3;

        /**
         * 业务补充的 system prompt，会追加到固定的协调者角色提示词之后。
         */
        private String systemPrompt;
    }

    @Data
    public static class ThreadPool {

        private Tool tool = new Tool();

        private Ltm ltm = new Ltm();

        @Data
        public static class Tool {
            private int corePoolSize = AgentConstant.DEFAULT_TOOL_CORE_POOL_SIZE;
            private int maxPoolSize = AgentConstant.DEFAULT_TOOL_MAX_POOL_SIZE;
            private int queueCapacity = AgentConstant.DEFAULT_TOOL_QUEUE_CAPACITY;
        }

        @Data
        public static class Ltm {
            private int corePoolSize = AgentConstant.DEFAULT_LTM_CORE_POOL_SIZE;
            private int maxPoolSize = AgentConstant.DEFAULT_LTM_MAX_POOL_SIZE;
            private int queueCapacity = AgentConstant.DEFAULT_LTM_QUEUE_CAPACITY;
        }
    }
}
