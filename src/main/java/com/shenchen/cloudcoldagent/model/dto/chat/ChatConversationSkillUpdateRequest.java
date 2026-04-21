package com.shenchen.cloudcoldagent.model.dto.chat;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 更新会话绑定 skills 请求
 */
@Data
public class ChatConversationSkillUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 id
     */
    private String conversationId;

    /**
     * 会话强制绑定的 skill 名称列表
     */
    private List<String> selectedSkills;
}
