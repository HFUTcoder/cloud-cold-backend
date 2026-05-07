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

/**
 * `Document` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "knowledge_document", camelToUnderline = false)
public class Document implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 所属用户 id
     */
    private Long userId;

    /**
     * 所属知识库 id
     */
    private Long knowledgeId;

    /**
     * 文档名称
     */
    private String documentName;

    /**
     * 文档访问链接（通常来自 MinIO）
     */
    private String documentUrl;

    /**
     * MinIO 对象名
     */
    private String objectName;

    /**
     * ES 中对应文档的 source 标识
     */
    private String documentSource;

    /**
     * 文档类型，例如 pdf / docx
     */
    private String fileType;

    /**
     * Content-Type
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 索引状态，例如 pending / indexed / failed
     */
    private String indexStatus;

    /**
     * 分片数量
     */
    private Integer chunkCount;

    /**
     * 索引失败原因
     */
    private String indexErrorMessage;

    /**
     * 索引开始时间
     */
    private LocalDateTime indexStartTime;

    /**
     * 索引结束时间
     */
    private LocalDateTime indexEndTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(isLogicDelete = true)
    private Integer isDelete;
}
