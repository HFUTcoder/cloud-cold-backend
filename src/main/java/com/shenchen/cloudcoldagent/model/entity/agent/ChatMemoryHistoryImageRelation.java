package com.shenchen.cloudcoldagent.model.entity.agent;

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
 * `ChatMemoryHistoryImageRelation` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "chat_memory_history_image_relation", camelToUnderline = false)
public class ChatMemoryHistoryImageRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private Long historyId;

    private String conversationId;

    private Long imageId;

    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
