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

    /**
     * 创建 `AgentPayload` 实例。
     *
     * @param type type 参数。
     * @param content content 参数。
     * @param count count 参数。
     * @param meta meta 参数。
     */
    private AgentPayload(String type, String content, Integer count, Map<String, Object> meta) {
        this.type = type;
        this.content = content;
        this.count = count;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    /**
     * 处理 `text` 对应逻辑。
     *
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload text(String content) {
        return new AgentPayload(TYPE_TEXT, content, null, Map.of());
    }

    /**
     * 处理 `final Answer` 对应逻辑。
     *
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload finalAnswer(String content) {
        return text(content);
    }

    /**
     * 处理 `error` 对应逻辑。
     *
     * @param message message 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload error(String message) {
        return new AgentPayload(TYPE_ERROR, message, null, Map.of());
    }

    /**
     * 处理 `thinking` 对应逻辑。
     *
     * @param stage stage 参数。
     * @param title title 参数。
     * @param content content 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload thinking(String stage, String title, String content) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stage", stage);
        meta.put("title", title);
        return new AgentPayload(TYPE_THINKING, content, null, meta);
    }

    /**
     * 处理 `action Required` 对应逻辑。
     *
     * @param content content 参数。
     * @param meta meta 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload actionRequired(String content, Map<String, Object> meta) {
        return new AgentPayload(TYPE_ACTION_REQUIRED, content, null, meta);
    }

    /**
     * 处理 `action Required` 对应逻辑。
     *
     * @param meta meta 参数。
     * @return 返回处理结果。
     */
    public static AgentPayload actionRequired(Map<String, Object> meta) {
        return actionRequired(null, meta);
    }

    /**
     * 获取 `get Type` 对应结果。
     *
     * @return 返回处理结果。
     */
    public String getType() {
        return type;
    }

    /**
     * 获取 `get Content` 对应结果。
     *
     * @return 返回处理结果。
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取 `get Count` 对应结果。
     *
     * @return 返回处理结果。
     */
    public Integer getCount() {
        return count;
    }

    /**
     * 获取 `get Meta` 对应结果。
     *
     * @return 返回处理结果。
     */
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
