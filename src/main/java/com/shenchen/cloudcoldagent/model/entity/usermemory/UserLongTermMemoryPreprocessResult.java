package com.shenchen.cloudcoldagent.model.entity.usermemory;

import java.util.List;

public record UserLongTermMemoryPreprocessResult(
        List<UserLongTermMemoryDoc> memories,
        String runtimePrompt,
        boolean retrievalTriggered
) {
}
