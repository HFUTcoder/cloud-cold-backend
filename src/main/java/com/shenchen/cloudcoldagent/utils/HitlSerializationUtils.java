package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.hitl.HITLAdvisor;
import com.shenchen.cloudcoldagent.hitl.HITLState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HitlSerializationUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HitlSerializationUtils() {
    }

    public static String writeMessages(List<Message> messages) {
        List<MessageSnapshot> snapshots = new ArrayList<>();
        if (messages != null) {
            for (Message message : messages) {
                if (message == null) {
                    continue;
                }
                snapshots.add(toSnapshot(message));
            }
        }
        return writeJson(snapshots);
    }

    public static List<Message> readMessages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<MessageSnapshot> snapshots = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
            List<Message> messages = new ArrayList<>();
            for (MessageSnapshot snapshot : snapshots) {
                if (snapshot == null) {
                    continue;
                }
                messages.add(fromSnapshot(snapshot));
            }
            return messages;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HITL checkpoint 消息反序列化失败");
        }
    }

    public static String writeContext(Map<String, Object> context) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (entry.getValue() instanceof HITLState hitlState) {
                    normalized.put(entry.getKey(), Map.of(
                            "__type", "HITLState",
                            "consumedToolCallIds", hitlState.snapshotConsumedToolCallIds()
                    ));
                } else {
                    normalized.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return writeJson(normalized);
    }

    public static Map<String, Object> readContext(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
            Map<String, Object> restored = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> valueMap
                        && "HITLState".equals(valueMap.get("__type"))) {
                    Object consumed = valueMap.get("consumedToolCallIds");
                    List<String> consumedToolCallIds = consumed instanceof List<?> list
                            ? list.stream().map(String::valueOf).toList()
                            : List.of();
                    restored.put(entry.getKey(), HITLState.fromConsumedToolCallIds(consumedToolCallIds));
                    continue;
                }
                restored.put(entry.getKey(), value);
            }
            restored.computeIfAbsent(HITLAdvisor.HITL_STATE_KEY, key -> new HITLState());
            return restored;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HITL checkpoint 上下文反序列化失败");
        }
    }

    public static <T> String writeJson(T value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HITL checkpoint JSON 序列化失败");
        }
    }

    public static <T> T readJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HITL checkpoint JSON 反序列化失败");
        }
    }

    private static MessageSnapshot toSnapshot(Message message) {
        MessageSnapshot snapshot = new MessageSnapshot();
        snapshot.setMessageType(message.getMessageType() == null ? null : message.getMessageType().name());
        snapshot.setText(message.getText());

        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            List<ToolCallSnapshot> toolCallSnapshots = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                toolCallSnapshots.add(new ToolCallSnapshot(toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments()));
            }
            snapshot.setToolCalls(toolCallSnapshots);
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            List<ToolResponseSnapshot> responseSnapshots = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                responseSnapshots.add(new ToolResponseSnapshot(response.id(), response.name(), response.responseData()));
            }
            snapshot.setToolResponses(responseSnapshots);
        }
        return snapshot;
    }

    private static Message fromSnapshot(MessageSnapshot snapshot) {
        MessageType messageType = snapshot.getMessageType() == null
                ? null
                : MessageType.valueOf(snapshot.getMessageType());
        if (messageType == MessageType.USER) {
            return new UserMessage(snapshot.getText());
        }
        if (messageType == MessageType.SYSTEM) {
            return new SystemMessage(snapshot.getText());
        }
        if (messageType == MessageType.TOOL) {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            if (snapshot.getToolResponses() != null) {
                for (ToolResponseSnapshot response : snapshot.getToolResponses()) {
                    responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), response.responseData()));
                }
            }
            return ToolResponseMessage.builder().responses(responses).build();
        }
        if (messageType == MessageType.ASSISTANT) {
            AssistantMessage.Builder builder = AssistantMessage.builder().content(snapshot.getText());
            if (snapshot.getToolCalls() != null && !snapshot.getToolCalls().isEmpty()) {
                List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                for (ToolCallSnapshot toolCall : snapshot.getToolCalls()) {
                    toolCalls.add(new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments()));
                }
                builder.toolCalls(toolCalls);
            }
            return builder.build();
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的消息类型");
    }

    public static class MessageSnapshot {
        private String messageType;
        private String text;
        private List<ToolCallSnapshot> toolCalls;
        private List<ToolResponseSnapshot> toolResponses;

        public String getMessageType() {
            return messageType;
        }

        public void setMessageType(String messageType) {
            this.messageType = messageType;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public List<ToolCallSnapshot> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCallSnapshot> toolCalls) {
            this.toolCalls = toolCalls;
        }

        public List<ToolResponseSnapshot> getToolResponses() {
            return toolResponses;
        }

        public void setToolResponses(List<ToolResponseSnapshot> toolResponses) {
            this.toolResponses = toolResponses;
        }
    }

    public record ToolCallSnapshot(String id, String type, String name, String arguments) {
    }

    public record ToolResponseSnapshot(String id, String name, String responseData) {
    }
}
