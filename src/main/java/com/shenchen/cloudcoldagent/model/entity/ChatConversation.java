package com.shenchen.cloudcoldagent.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `ChatConversation` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "chat_conversation", camelToUnderline = false)
public class ChatConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private String conversationId;

    private String title;

    /**
     * 反序列化后的 skill 名称列表，仅用于接口返回。
     */
    @Column(ignore = true)
    private List<String> selectedSkillList;

    /**
     * 当前会话绑定的知识库 id，仅用于接口返回。
     */
    @Column(ignore = true)
    private Long selectedKnowledgeId;

    /**
     * 当前会话绑定的知识库名称，仅用于接口返回。
     */
    @Column(ignore = true)
    private String selectedKnowledgeName;

    private LocalDateTime lastActiveTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
