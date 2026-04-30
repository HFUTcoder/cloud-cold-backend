package com.shenchen.cloudcoldagent.model.dto.chat;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChatConversationKnowledgeUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String conversationId;

    private Long knowledgeId;
}
