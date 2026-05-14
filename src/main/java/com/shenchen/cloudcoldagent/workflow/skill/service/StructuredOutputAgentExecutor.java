package com.shenchen.cloudcoldagent.workflow.skill.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.utils.JsonUtil;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * `StructuredOutputAgentExecutor` 类型实现。
 */
@Component
public class StructuredOutputAgentExecutor {

    private final ChatModel chatModel;

    private final ObjectMapper objectMapper;

    public StructuredOutputAgentExecutor(ChatModel chatModel,
                                         ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(List<Message> messages, Class<T> outputType) {
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("StructuredOutputAgent")
                    .model(chatModel)
                    .outputType(outputType)
                    .build();
            return parseResponse(agent.call(messages), outputType);
        } catch (Exception e) {
            throw new RuntimeException("structured output 执行失败", e);
        }
    }

    public <T> T execute(List<Message> messages,
                         Class<T> outputType,
                         BeanOutputConverter<T> converter) {
        if (converter == null) {
            return execute(messages, outputType);
        }
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("StructuredOutputAgent")
                    .model(chatModel)
                    .outputType(outputType)
                    .outputSchema(converter.getJsonSchema())
                    .build();
            return parseResponse(agent.call(messages), outputType);
        } catch (Exception e) {
            throw new RuntimeException("structured output 执行失败", e);
        }
    }

    public <T> T executeWithSchema(List<Message> messages,
                                   Class<T> outputType,
                                   String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return execute(messages, outputType);
        }
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("StructuredOutputAgent")
                    .model(chatModel)
                    .outputType(outputType)
                    .outputSchema(outputSchema)
                    .build();
            return parseResponse(agent.call(messages), outputType);
        } catch (Exception e) {
            throw new RuntimeException("structured output 执行失败", e);
        }
    }

    private <T> T parseResponse(AssistantMessage response, Class<T> outputType) {
        try {
            String text = response == null ? null : response.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("structured output 返回为空");
            }
            try {
                return objectMapper.readValue(text, outputType);
            } catch (Exception firstEx) {
                try {
                    return JsonUtil.fixAndParse(text, outputType);
                } catch (Exception secondEx) {
                    throw new RuntimeException("structured output 响应解析失败", firstEx);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("structured output 响应解析失败", e);
        }
    }

}
