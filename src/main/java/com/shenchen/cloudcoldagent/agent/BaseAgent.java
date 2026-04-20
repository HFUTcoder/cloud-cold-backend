package com.shenchen.cloudcoldagent.agent;

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
    protected final int maxRounds;
    protected final ChatMemory chatMemory;

    protected BaseAgent(ChatModel chatModel, List<ToolCallback> tools, int maxRounds, ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.tools = tools == null ? Collections.emptyList() : new ArrayList<>(tools);
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;
    }

    protected boolean useMemory(String conversationId) {
        return conversationId != null && chatMemory != null;
    }

    protected List<Message> getMemoryMessages(String conversationId) {
        if (!useMemory(conversationId)) {
            return List.of();
        }
        List<Message> history = chatMemory.get(conversationId);
        return history == null ? List.of() : history;
    }

    protected void addUserMemory(String conversationId, String question) {
        if (useMemory(conversationId)) {
            chatMemory.add(conversationId, new UserMessage(question));
        }
    }

    protected void addAssistantMemory(String conversationId, String answer) {
        if (useMemory(conversationId)) {
            chatMemory.add(conversationId, new AssistantMessage(answer));
        }
    }
}
