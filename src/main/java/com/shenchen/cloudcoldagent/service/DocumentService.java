package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;

import java.util.List;

public interface DocumentService extends IService<com.shenchen.cloudcoldagent.model.entity.Document> {

    long addDocument(Long userId, DocumentAddRequest request);

    boolean updateDocument(Long userId, DocumentUpdateRequest request);

    boolean deleteDocument(Long userId, Long id);

    com.shenchen.cloudcoldagent.model.entity.Document getDocumentById(Long userId, Long id);

    List<com.shenchen.cloudcoldagent.model.entity.Document> listByKnowledgeId(Long userId, Long knowledgeId);

    Page<com.shenchen.cloudcoldagent.model.entity.Document> pageByUserId(Long userId, DocumentQueryRequest request);

    QueryWrapper getQueryWrapper(Long userId, DocumentQueryRequest request);

    DocumentVO getDocumentVO(com.shenchen.cloudcoldagent.model.entity.Document document);

    List<DocumentVO> getDocumentVOList(List<com.shenchen.cloudcoldagent.model.entity.Document> documents);
}
