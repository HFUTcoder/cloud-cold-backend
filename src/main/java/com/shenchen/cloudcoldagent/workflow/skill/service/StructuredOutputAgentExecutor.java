package com.shenchen.cloudcoldagent.workflow.skill.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * 创建 `StructuredOutputAgentExecutor` 实例。
     *
     * @param chatModel chatModel 参数。
     * @param objectMapper objectMapper 参数。
     */
    public StructuredOutputAgentExecutor(ChatModel chatModel,
                                         ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 `execute` 对应逻辑。
     *
     * @param messages messages 参数。
     * @param outputType outputType 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 执行 `execute` 对应逻辑。
     *
     * @param messages messages 参数。
     * @param outputType outputType 参数。
     * @param converter converter 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 执行 `execute With Schema` 对应逻辑。
     *
     * @param messages messages 参数。
     * @param outputType outputType 参数。
     * @param outputSchema outputSchema 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 解析 `parse Response` 对应内容。
     *
     * @param response response 参数。
     * @param outputType outputType 参数。
     * @return 返回处理结果。
     */
    private <T> T parseResponse(AssistantMessage response, Class<T> outputType) {
        try {
            String text = response == null ? null : response.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("structured output 返回为空");
            }
            try {
                return objectMapper.readValue(text, outputType);
            } catch (Exception firstEx) {
                String normalizedJson = normalizeJsonPayload(text);
                if (normalizedJson == null || normalizedJson.isBlank()) {
                    throw firstEx;
                }
                return objectMapper.readValue(normalizedJson, outputType);
            }
        } catch (Exception e) {
            throw new RuntimeException("structured output 响应解析失败", e);
        }
    }

    /**
     * 处理 `normalize Json Payload` 对应逻辑。
     *
     * @param rawText rawText 参数。
     * @return 返回处理结果。
     */
    private String normalizeJsonPayload(String rawText) {
        if (rawText == null) {
            return null;
        }
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            int fenceEnd = trimmed.lastIndexOf("```");
            if (firstLineBreak >= 0 && fenceEnd > firstLineBreak) {
                trimmed = trimmed.substring(firstLineBreak + 1, fenceEnd).trim();
            }
        }

        int start = findJsonStart(trimmed);
        if (start < 0) {
            return trimmed;
        }
        String extracted = extractBalancedJson(trimmed, start);
        return extracted == null || extracted.isBlank() ? trimmed : extracted;
    }

    /**
     * 查找 `find Json Start` 对应结果。
     *
     * @param text text 参数。
     * @return 返回处理结果。
     */
    private int findJsonStart(String text) {
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    /**
     * 提取 `extract Balanced Json` 对应内容。
     *
     * @param text text 参数。
     * @param startIndex startIndex 参数。
     * @return 返回处理结果。
     */
    private String extractBalancedJson(String text, int startIndex) {
        if (startIndex < 0 || startIndex >= text.length()) {
            return null;
        }
        char opening = text.charAt(startIndex);
        char closing = opening == '{' ? '}' : (opening == '[' ? ']' : '\0');
        if (closing == '\0') {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = startIndex; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == opening) {
                depth++;
                continue;
            }
            if (ch == closing) {
                depth--;
                if (depth == 0) {
                    return text.substring(startIndex, i + 1);
                }
            }
        }
        return null;
    }

}
