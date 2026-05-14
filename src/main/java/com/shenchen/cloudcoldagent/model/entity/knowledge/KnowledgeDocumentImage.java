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
 * `KnowledgeDocumentImage` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "knowledge_document_image", camelToUnderline = false)
public class KnowledgeDocumentImage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private Long userId;

    private Long knowledgeId;

    private Long documentId;

    private Integer imageIndex;

    private String objectName;

    private String imageUrl;

    private String contentType;

    private Long fileSize;

    private Integer pageNumber;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
