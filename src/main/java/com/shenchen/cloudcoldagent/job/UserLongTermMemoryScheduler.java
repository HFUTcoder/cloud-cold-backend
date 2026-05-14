package com.shenchen.cloudcoldagent.job;

import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryMetadataService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * `UserLongTermMemoryScheduler` 类型实现。
 */
@Component
@Slf4j
public class UserLongTermMemoryScheduler {

    private final LongTermMemoryProperties properties;
    private final UserLongTermMemoryMetadataService metadataService;
    private final UserLongTermMemoryService userLongTermMemoryService;

    public UserLongTermMemoryScheduler(LongTermMemoryProperties properties,
                                       UserLongTermMemoryMetadataService metadataService,
                                       UserLongTermMemoryService userLongTermMemoryService) {
        this.properties = properties;
        this.metadataService = metadataService;
        this.userLongTermMemoryService = userLongTermMemoryService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    public void processPendingMemoriesOnTheHour() {
        if (!properties.isEnabled()) {
            return;
        }
        for (Long userId : metadataService.listUserIdsWithPendingConversationStates()) {
            if (userId == null || userId <= 0) {
                continue;
            }
            try {
                userLongTermMemoryService.processPendingConversations(userId);
            } catch (Exception e) {
                log.warn("整点处理长期记忆失败，userId={}, message={}", userId, e.getMessage(), e);
            }
        }
    }
}
