package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * `LongTermMemoryProperties` 类型实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.long-term-memory")
public class LongTermMemoryProperties {

    /**
     * 是否启用长期记忆。
     */
    private boolean enabled = true;

    /**
     * 触发一次长期记忆重建所需的新增轮次数。
     */
    private int triggerRounds = 6;

    /**
     * 问题检索长期记忆时的 topK。
     */
    private int retrieveTopK = 5;

    /**
     * 长期记忆向量检索的相似度阈值。
     */
    private double similarityThreshold = 0.5d;

    /**
     * 注入 Agent 上下文时允许的最大条数。
     */
    private int maxPromptMemories = 4;

    /**
     * 记忆内容最大字符数。
     */
    private int maxMemoryChars = 400;

    /**
     * 宠物默认名称。
     */
    private String defaultPetName = "小云";

    /**
     * 宠物情绪显示为"刚刚学到"的时间阈值（分钟）。
     */
    private int updatedMoodMinutes = 10;

    /**
     * 宠物名称和学习时间 Redis key 的 TTL（天）。
     */
    private int petNameTtlDays = 30;

    /**
     * 记忆列表拉取上限。
     */
    private int listLimit = 100;

    /**
     * 会话历史截断字符数（送入 LLM 提取前）。
     */
    private int transcriptTruncateChars = 1200;

    /**
     * fallback 记忆置信度。
     */
    private double fallbackConfidence = 0.45d;

    /**
     * fallback 记忆重要度。
     */
    private double fallbackImportance = 0.5d;

    /**
     * 记忆标题截断字符数。
     */
    private int titleTruncateChars = 40;

    /**
     * 记忆摘要截断字符数。
     */
    private int summaryTruncateChars = 60;

    /**
     * 长期记忆关键词索引名。
     */
    private String keywordIndexName = "user_long_term_memory_docs";

    /**
     * 长期记忆向量索引名。
     */
    private String vectorIndexName = "user_long_term_memory_vector";

    /**
     * 长期记忆向量维度。
     */
    private int vectorDimensions = 1536;
}
