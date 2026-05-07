package com.shenchen.cloudcoldagent.model.dto.document;

import com.shenchen.cloudcoldagent.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * `DocumentQueryRequest` 类型实现。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class DocumentQueryRequest extends PageRequest implements Serializable {

    private Long id;

    private Long knowledgeId;

    private String documentName;

    private String fileType;

    private String indexStatus;

    private static final long serialVersionUID = 1L;
}
