package com.shenchen.cloudcoldagent.agent;

import com.shenchen.cloudcoldagent.model.entity.record.support.NormalizationResult;
import com.shenchen.cloudcoldagent.utils.JsonArgumentUtils;
import com.shenchen.cloudcoldagent.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Agent 基类，统一承载模型、工具、轮次和记忆相关公共能力。
 */
@Slf4j
public abstract class BaseAgent {

    public static final String TOOL_CALL_TYPE_FUNCTION = "function";
    public static final String TOOL_CALL_ID_PREFIX = "call_";
    public static final String EXECUTION_PLAN_HEADER = "【Execution Plan】\n";
    public static final String QUESTION_TAG_START = "<question>";
    public static final String QUESTION_TAG_END = "</question>";

    protected final String name;
    protected final ChatModel chatModel;
    protected final List<ToolCallback> tools;
    protected final List<Advisor> advisors;
    protected final String systemPrompt;
    protected final int maxRounds;
    protected final ChatMemory chatMemory;
    protected final Executor toolExecutor;
    protected final Executor virtualThreadExecutor;
    protected final Semaphore toolSemaphore;

    /**
     * 初始化 Agent 运行所需的公共依赖，包括模型、工具集、advisor、最大轮次和会话记忆。
     *
     * @param name Agent 名称，用于日志标识。
     * @param chatModel Agent 使用的底层大模型。
     * @param tools 当前 Agent 可调用的工具集合。
     * @param advisors 挂载到模型请求链路上的 advisor 集合。
     * @param systemPrompt 业务侧补充的 system prompt。
     * @param maxRounds Agent 单次执行允许的最大轮次。
     * @param chatMemory 会话级记忆存储，可为空。
     * @param toolConcurrency 工具并发调用上限。
     * @param toolExecutor 工具调用线程池。
     * @param virtualThreadExecutor 虚拟线程执行器。
     */
    protected BaseAgent(String name, ChatModel chatModel, List<ToolCallback> tools, List<Advisor> advisors,
                        String systemPrompt, int maxRounds, ChatMemory chatMemory,
                        int toolConcurrency, Executor toolExecutor, Executor virtualThreadExecutor) {
        this.name = name;
        this.chatModel = chatModel;
        this.tools = tools == null ? Collections.emptyList() : new ArrayList<>(tools);
        this.advisors = advisors == null ? Collections.emptyList() : new ArrayList<>(advisors);
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;
        this.toolExecutor = toolExecutor;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.toolSemaphore = new Semaphore(Math.max(1, toolConcurrency));
    }

    /**
     * 根据工具名称查找对应的 ToolCallback。
     *
     * @param toolName 工具名称。
     * @return 找到的 ToolCallback，未找到时返回 null。
     */
    protected ToolCallback findTool(String toolName) {
        if (tools == null || StringUtils.isBlank(toolName)) {
            return null;
        }
        return tools.stream()
                .filter(tool -> tool != null && tool.getToolDefinition() != null)
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断当前请求是否启用了会话记忆。
     *
     * @param conversationId 当前会话 id。
     * @return 当会话 id 和记忆仓库都存在时返回 true。
     */
    protected boolean useMemory(String conversationId) {
        return conversationId != null && chatMemory != null;
    }

    /**
     * 读取指定会话的历史消息列表。
     *
     * @param conversationId 当前会话 id。
     * @return 会话对应的历史消息；未启用记忆时返回空列表。
     */
    protected List<Message> getMemoryMessages(String conversationId) {
        if (!useMemory(conversationId)) {
            return List.of();
        }
        List<Message> history = chatMemory.get(conversationId);
        return history == null ? List.of() : history;
    }

    /**
     * 将本轮用户输入写入会话记忆。
     *
     * @param conversationId 当前会话 id。
     * @param question 用户问题文本。
     */
    protected void addUserMemory(String conversationId, String question) {
        if (useMemory(conversationId)) {
            chatMemory.add(conversationId, new UserMessage(question));
        }
    }

    /**
     * 将助手最终回复写入会话记忆。
     *
     * @param conversationId 当前会话 id。
     * @param answer 助手最终回答文本。
     */
    protected void addAssistantMemory(String conversationId, String answer) {
        if (useMemory(conversationId)) {
            chatMemory.add(conversationId, new AssistantMessage(answer));
        }
    }

    /**
     * 将用户问题包装为带 XML 标签的格式。
     *
     * @param question 用户问题文本。
     * @return 包装后的问题文本。
     */
    protected String wrapQuestion(String question) {
        return QUESTION_TAG_START + question + QUESTION_TAG_END;
    }

    /**
     * 汇总当前 Agent 可用工具描述，供规划阶段注入到提示词中。
     *
     * @return 工具名称和说明拼接成的文本。
     */
    protected String renderToolDescriptions() {
        if (tools == null || tools.isEmpty()) {
            return "（当前无可用工具）";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCallback tool : tools) {
            sb.append("- ")
                    .append(tool.getToolDefinition().name())
                    .append(": ")
                    .append(tool.getToolDefinition().description())
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 规范化工具调用列表，统一 type 为 function 并规范化参数 JSON。
     */
    protected List<AssistantMessage.ToolCall> normalizeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> normalized = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            normalized.add(new AssistantMessage.ToolCall(
                    toolCall.id(),
                    TOOL_CALL_TYPE_FUNCTION,
                    toolCall.name(),
                    normalizeArguments(toolCall)
            ));
        }
        return normalized;
    }

    /**
     * 规范化单个工具调用的参数 JSON。
     */
    protected String normalizeArguments(AssistantMessage.ToolCall toolCall) {
        String rawArguments = toolCall == null ? null : toolCall.arguments();
        NormalizationResult result = JsonArgumentUtils.normalizeJsonArguments(rawArguments);
        if (!result.valid()) {
            log.warn("工具调用参数不是合法 JSON，toolName={}, toolId={}, rawArguments={}, error={}",
                    toolCall == null ? null : toolCall.name(),
                    toolCall == null ? null : toolCall.id(),
                    rawArguments,
                    result.errorMessage());
            return rawArguments;
        }
        return result.normalizedJson();
    }

    /**
     * 向消息列表中添加工具执行错误的响应消息。
     */
    protected void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        String errorJson;
        try {
            errorJson = "{\"error\":" + JsonUtil.objectMapper().writeValueAsString(errMsg) + "}";
        } catch (Exception ex) {
            errorJson = "{\"error\":\"tool execution failed\"}";
        }
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                errorJson
        );
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }
}
