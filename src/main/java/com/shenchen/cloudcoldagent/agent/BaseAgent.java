package com.shenchen.cloudcoldagent.agent;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 基类，统一承载模型、工具、轮次和记忆相关公共能力。
 */
public abstract class BaseAgent {

    protected final ChatModel chatModel;
    protected final List<ToolCallback> tools;
    protected final List<Advisor> advisors;
    protected final int maxRounds;
    protected final ChatMemory chatMemory;

    /**
     * 初始化 Agent 运行所需的公共依赖，包括模型、工具集、advisor、最大轮次和会话记忆。
     *
     * @param chatModel Agent 使用的底层大模型。
     * @param tools 当前 Agent 可调用的工具集合。
     * @param advisors 挂载到模型请求链路上的 advisor 集合。
     * @param maxRounds Agent 单次执行允许的最大轮次。
     * @param chatMemory 会话级记忆存储，可为空。
     */
    protected BaseAgent(ChatModel chatModel, List<ToolCallback> tools, List<Advisor> advisors, int maxRounds, ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.tools = tools == null ? Collections.emptyList() : new ArrayList<>(tools);
        this.advisors = advisors == null ? Collections.emptyList() : new ArrayList<>(advisors);
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;
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
}
