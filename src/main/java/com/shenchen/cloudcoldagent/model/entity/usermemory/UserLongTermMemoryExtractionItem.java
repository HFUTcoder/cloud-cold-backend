package com.shenchen.cloudcoldagent.model.entity.usermemory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * `UserLongTermMemoryExtractionItem` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLongTermMemoryExtractionItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String memoryType;

    private String title;

    private String content;

    private String summary;

    private Double confidence;

    private Double importance;

    private List<Long> sourceHistoryIds;
}
