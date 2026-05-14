package com.shenchen.cloudcoldagent.service.knowledge;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.vo.knowledge.DocumentVO;

import com.shenchen.cloudcoldagent.model.entity.knowledge.Document;
import java.util.List;

/**
 * `DocumentService` 接口定义。
 */
public interface DocumentService extends IService<Document> {

    long addDocument(Long userId, DocumentAddRequest request);

    boolean updateDocument(Long userId, DocumentUpdateRequest request);

    boolean deleteDocument(Long userId, Long id);

    Document getDocumentById(Long userId, Long id);

    List<Document> listByKnowledgeId(Long userId, Long knowledgeId);

    Page<Document> pageByUserId(Long userId, DocumentQueryRequest request);

    QueryWrapper getQueryWrapper(Long userId, DocumentQueryRequest request);

    DocumentVO getDocumentVO(Document document);

    List<DocumentVO> getDocumentVOList(List<Document> documents);
}
