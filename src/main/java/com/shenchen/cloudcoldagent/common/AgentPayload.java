package com.shenchen.cloudcoldagent.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一抽象 Agent 响应片段的业务载荷，借鉴 AgentResponse 的类型化设计，
 * 但保持对外事件结构兼容，便于在现有 agent 体系中渐进复用。
 */
public final class AgentPayload {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_REFERENCE = "reference";
    public static final String TYPE_RECOMMEND = "recommend";
    public static final String TYPE_ACTION_REQUIRED = "action_required";

    private final String type;
    private final String content;
    private final Integer count;
    private final Map<String, Object> meta;

    private AgentPayload(String type, String content, Integer count, Map<String, Object> meta) {
        this.type = type;
        this.content = content;
        this.count = count;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static AgentPayload text(String content) {
        return new AgentPayload(TYPE_TEXT, content, null, Map.of());
    }

    public static AgentPayload finalAnswer(String content) {
        return text(content);
    }

    public static AgentPayload error(String message) {
        return new AgentPayload(TYPE_ERROR, message, null, Map.of());
    }

    public static AgentPayload thinking(String stage, String title, String content) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stage", stage);
        meta.put("title", title);
        return new AgentPayload(TYPE_THINKING, content, null, meta);
    }

    public static AgentPayload actionRequired(String content, Map<String, Object> meta) {
        return new AgentPayload(TYPE_ACTION_REQUIRED, content, null, meta);
    }

    public static AgentPayload actionRequired(Map<String, Object> meta) {
        return actionRequired(null, meta);
    }

    public String getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public Integer getCount() {
        return count;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    /**
     * 兼容现有前端协议：只返回当前事件 data 所需的旧结构，不额外暴露 payload type。
     */
    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (type) {
            case TYPE_ERROR -> {
                data.put("message", content == null ? "" : content);
                data.putAll(meta);
            }
            case TYPE_THINKING -> {
                data.putAll(meta);
                data.put("content", content);
                if (count != null) {
                    data.put("count", count);
                }
            }
            case TYPE_REFERENCE, TYPE_RECOMMEND, TYPE_ACTION_REQUIRED -> {
                if (content != null) {
                    data.put("content", content);
                }
                if (count != null) {
                    data.put("count", count);
                }
                data.putAll(meta);
            }
            case TYPE_TEXT -> {
                data.put("content", content);
                if (count != null) {
                    data.put("count", count);
                }
                data.putAll(meta);
            }
            default -> {
                if (content != null) {
                    data.put("content", content);
                }
                if (count != null) {
                    data.put("count", count);
                }
                data.putAll(meta);
            }
        }
        return data;
    }
}
