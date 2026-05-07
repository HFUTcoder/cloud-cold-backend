package com.shenchen.cloudcoldagent.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `ChatMemoryHistoryVO` 类型实现。
 */
@Data
public class ChatMemoryHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String conversationId;

    private String content;

    private String messageType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private List<RetrievedKnowledgeImage> retrievedImages;
}
