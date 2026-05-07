package com.shenchen.cloudcoldagent.model.entity.usermemory;

import java.util.List;

/**
 * 创建 `UserLongTermMemoryPreprocessResult` 实例。
 *
 * @param memories memories 参数。
 * @param runtimePrompt runtimePrompt 参数。
 * @param retrievalTriggered retrievalTriggered 参数。
 */
/**
 * `UserLongTermMemoryPreprocessResult` 记录对象。
 */
public record UserLongTermMemoryPreprocessResult(
        List<UserLongTermMemoryDoc> memories,
        String runtimePrompt,
        boolean retrievalTriggered
) {
}
