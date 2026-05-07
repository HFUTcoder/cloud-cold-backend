package com.shenchen.cloudcoldagent.model.vo.usermemory;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `UserLongTermMemoryVO` 类型实现。
 */
@Data
public class UserLongTermMemoryVO {

    private String id;

    private String status;

    private String memoryType;

    private String title;

    private String content;

    private String summary;

    private Double confidence;

    private Double importance;

    private List<String> sourceConversationIds;

    private Integer sourceHistoryCount;

    private LocalDateTime lastRetrievedAt;

    private LocalDateTime lastReinforcedAt;

    private LocalDateTime updatedAt;
}
