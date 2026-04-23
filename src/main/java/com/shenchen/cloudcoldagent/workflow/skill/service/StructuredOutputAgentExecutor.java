package com.shenchen.cloudcoldagent.workflow.skill.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StructuredOutputAgentExecutor {

    private final ChatModel chatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public StructuredOutputAgentExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public <T> T execute(List<Message> messages, Class<T> outputType) {
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("StructuredOutputAgent")
                    .model(chatModel)
                    .outputType(outputType)
                    .build();
            AssistantMessage response = agent.call(messages);
            String text = response == null ? null : response.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("structured output 返回为空");
            }
            return objectMapper.readValue(text, outputType);
        } catch (Exception e) {
            throw new RuntimeException("structured output 执行失败", e);
        }
    }
}
