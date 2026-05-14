package com.shenchen.cloudcoldagent.model.entity.knowledge;

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
 * `Knowledge` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "knowledge", camelToUnderline = false)
public class Knowledge implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 所属用户 id
     */
    private Long userId;

    /**
     * 知识库名称
     */
    private String knowledgeName;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 当前知识库下的文档数量
     */
    private Integer documentCount;

    /**
     * 最后一次上传文档时间
     */
    private LocalDateTime lastDocumentUploadTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
