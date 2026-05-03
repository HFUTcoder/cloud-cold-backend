package com.shenchen.cloudcoldagent.model.entity.usermemory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLongTermMemoryDoc {

    private String id;

    private Long userId;

    private String memoryType;

    private String title;

    private String content;

    private String summary;

    private String embeddingText;

    private Double confidence;

    private Double importance;

    private List<String> sourceConversationIds;

    private List<Long> sourceHistoryIds;

    private Map<Long, String> sourceConversationsByHistoryId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
