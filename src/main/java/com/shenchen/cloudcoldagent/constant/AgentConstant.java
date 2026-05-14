package com.shenchen.cloudcoldagent.constant;

/**
 * Agent 相关默认配置常量。
 */
public interface AgentConstant {

    // ==================== 通用 ====================

    String DEFAULT_TIMEZONE = "Asia/Shanghai";

    // ==================== 记忆 ====================

    int DEFAULT_MEMORY_MAX_MESSAGES = 20;

    // ==================== React Agent ====================

    String DEFAULT_REACT_NAME = "ReactAgent";

    int DEFAULT_REACT_MAX_ROUNDS = 5;

    int DEFAULT_REACT_TOOL_CONCURRENCY = 3;

    // ==================== PlanExecute Agent ====================

    String DEFAULT_PLAN_NAME = "PlanExecuteAgent";

    String DEFAULT_PLAN_AGENT_TYPE = "PlanExecuteAgent";

    int DEFAULT_PLAN_MAX_ROUNDS = 5;

    int DEFAULT_PLAN_MAX_TOOL_RETRIES = 5;

    int DEFAULT_PLAN_CONTEXT_CHAR_LIMIT = 5000;

    int DEFAULT_PLAN_TOOL_CONCURRENCY = 3;

    long DEFAULT_PLAN_TOOL_BATCH_TIMEOUT_SECONDS = 60;

    // ==================== 线程池 ====================

    int DEFAULT_TOOL_CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    int DEFAULT_TOOL_MAX_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    int DEFAULT_TOOL_QUEUE_CAPACITY = 100;

    int DEFAULT_LTM_CORE_POOL_SIZE = 1;

    int DEFAULT_LTM_MAX_POOL_SIZE = 4;

    int DEFAULT_LTM_QUEUE_CAPACITY = 100;
}
