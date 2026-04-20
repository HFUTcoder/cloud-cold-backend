package com.shenchen.cloudcoldagent.model.dto.chat;

import lombok.Data;

import java.io.Serializable;

/**
 * 删除会话请求
 */
@Data
public class ChatConversationDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 id
     */
    private String conversationId;
}
