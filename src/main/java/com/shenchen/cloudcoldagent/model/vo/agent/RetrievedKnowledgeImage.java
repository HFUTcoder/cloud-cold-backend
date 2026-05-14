package com.shenchen.cloudcoldagent.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * `RetrievedKnowledgeImage` 类型实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedKnowledgeImage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long imageId;

    private String imageUrl;

    private Integer pageNumber;

    private Long documentId;

    private String documentName;
}
