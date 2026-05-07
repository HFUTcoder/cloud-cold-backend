package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * `KnowledgeDocumentIngestionService` 接口定义。
 */
public interface KnowledgeDocumentIngestionService {

    /**
     * 处理 `upload Document` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @param file file 参数。
     * @return 返回处理结果。
     */
    DocumentVO uploadDocument(Long userId, Long knowledgeId, MultipartFile file);
}
