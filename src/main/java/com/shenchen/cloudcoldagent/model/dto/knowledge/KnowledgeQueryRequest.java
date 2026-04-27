package com.shenchen.cloudcoldagent.model.dto.knowledge;

import com.shenchen.cloudcoldagent.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class KnowledgeQueryRequest extends PageRequest implements Serializable {

    private Long id;

    private String knowledgeName;

    private String description;

    private static final long serialVersionUID = 1L;
}
