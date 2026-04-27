package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentIngestionService {

    DocumentVO uploadDocument(Long userId, Long knowledgeId, MultipartFile file);
}
