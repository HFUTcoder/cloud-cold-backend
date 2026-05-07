package com.shenchen.cloudcoldagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具基类，统一提供日志、mock 判断、异常处理和文本裁剪等辅助能力。
 */
public abstract class BaseTool {

    private static final int DEFAULT_LOG_TEXT_LIMIT = 4000;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final boolean mockEnabled;

    /**
     * 初始化工具公共配置。
     *
     * @param mockEnabled 是否启用 mock 返回。
     */
    protected BaseTool(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    /**
     * 判断当前工具是否开启了 mock 模式。
     *
     * @return 开启 mock 时返回 true。
     */
    protected boolean isMockEnabled() {
        return mockEnabled;
    }

    /**
     * 记录工具开始执行时的输入摘要。
     *
     * @param toolName 工具名称。
     * @param inputLabel 输入标签。
     * @param inputValue 输入内容摘要。
     */
    protected void logToolStart(String toolName, String inputLabel, String inputValue) {
        log.info("EXECUTE Tool: {} {}: {}", toolName, inputLabel, defaultText(inputValue));
    }

    /**
     * 记录工具成功执行后的输出摘要。
     *
     * @param toolName 工具名称。
     * @param inputValue 输入内容摘要。
     * @param output 工具输出文本。
     */
    protected void logToolSuccess(String toolName, String inputValue, String output) {
        log.info("EXECUTE Tool: {} result for input [{}]:\n{}",
                toolName,
                defaultText(inputValue),
                truncateText(output, DEFAULT_LOG_TEXT_LIMIT));
    }

    /**
     * 记录工具在 mock 模式下返回的结果。
     *
     * @param toolName 工具名称。
     * @param inputValue 输入内容摘要。
     * @param output mock 输出文本。
     */
    protected void logToolMock(String toolName, String inputValue, String output) {
        log.info("EXECUTE Tool: {} mock result for input [{}]:\n{}",
                toolName,
                defaultText(inputValue),
                truncateText(output, DEFAULT_LOG_TEXT_LIMIT));
    }

    /**
     * 统一记录工具异常并拼装返回给模型的错误消息。
     *
     * @param toolName 工具名称。
     * @param inputValue 输入内容摘要。
     * @param exception 执行时抛出的异常。
     * @param userMessagePrefix 返回给模型的错误前缀。
     * @return 可直接返回给上层的错误文本。
     */
    protected String handleToolException(String toolName, String inputValue, Exception exception, String userMessagePrefix) {
        log.error("EXECUTE Tool: {} failed, input: {}", toolName, defaultText(inputValue), exception);
        return userMessagePrefix + defaultText(exception.getMessage());
    }

    /**
     * 将超长文本截断到指定长度，避免日志刷屏。
     *
     * @param text 原始文本。
     * @param maxLength 允许保留的最大长度。
     * @return 截断后的文本；无需截断时返回原文。
     */
    protected String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength || maxLength <= 0) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    /**
     * 将空文本转换为统一的占位值，方便日志展示。
     *
     * @param text 原始文本。
     * @return 非空白时返回原文，否则返回“无”。
     */
    protected String defaultText(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }
}
