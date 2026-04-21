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

    protected BaseTool(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    protected boolean isMockEnabled() {
        return mockEnabled;
    }

    protected void logToolStart(String toolName, String inputLabel, String inputValue) {
        log.info("EXECUTE Tool: {} {}: {}", toolName, inputLabel, defaultText(inputValue));
    }

    protected void logToolSuccess(String toolName, String inputValue, String output) {
        log.info("EXECUTE Tool: {} result for input [{}]:\n{}",
                toolName,
                defaultText(inputValue),
                truncateText(output, DEFAULT_LOG_TEXT_LIMIT));
    }

    protected void logToolMock(String toolName, String inputValue, String output) {
        log.info("EXECUTE Tool: {} mock result for input [{}]:\n{}",
                toolName,
                defaultText(inputValue),
                truncateText(output, DEFAULT_LOG_TEXT_LIMIT));
    }

    protected String handleToolException(String toolName, String inputValue, Exception exception, String userMessagePrefix) {
        log.error("EXECUTE Tool: {} failed, input: {}", toolName, defaultText(inputValue), exception);
        return userMessagePrefix + defaultText(exception.getMessage());
    }

    protected String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength || maxLength <= 0) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    protected String defaultText(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }
}
