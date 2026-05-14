package com.shenchen.cloudcoldagent.service.knowledge;

import com.shenchen.cloudcoldagent.model.vo.knowledge.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * `KnowledgeDocumentIngestionService` 接口定义。
 */
public interface KnowledgeDocumentIngestionService {

    DocumentVO uploadDocument(Long userId, Long knowledgeId, MultipartFile file);
}
