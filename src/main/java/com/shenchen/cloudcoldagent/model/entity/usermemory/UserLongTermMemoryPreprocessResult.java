package com.shenchen.cloudcoldagent.model.entity.usermemory;

import java.util.List;

/**
 * 长期记忆预处理结果，包含召回的记忆和可注入 agent 上下文的 runtime prompt。
 *
 * @param memories 召回的长期记忆文档。
 * @param runtimePrompt 注入 agent 上下文的提示词。
 */
public record UserLongTermMemoryPreprocessResult(
        List<UserLongTermMemoryDoc> memories,
        String runtimePrompt
) {
}
