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

/**
 * `UserLongTermMemory` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "user_long_term_memory", camelToUnderline = false)
public class UserLongTermMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private String memoryId;

    private Long userId;

    private String memoryType;

    private String title;

    private String content;

    private String summary;

    private Double confidence;

    private Double importance;

    private String originConversationId;

    private String status;

    private LocalDateTime lastRetrievedAt;

    private LocalDateTime lastReinforcedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
