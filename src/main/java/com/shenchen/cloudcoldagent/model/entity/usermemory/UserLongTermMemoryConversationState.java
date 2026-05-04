package com.shenchen.cloudcoldagent.model.entity.usermemory;

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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "user_long_term_memory_conversation_state", camelToUnderline = false)
public class UserLongTermMemoryConversationState implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private Long userId;

    private String conversationId;

    private String status;

    private Integer pendingCompletedRounds;

    private LocalDateTime lastBuiltAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
