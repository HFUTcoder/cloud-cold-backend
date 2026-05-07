package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;

import java.util.List;

/**
 * `DocumentService` 接口定义。
 */
public interface DocumentService extends IService<com.shenchen.cloudcoldagent.model.entity.Document> {

    /**
     * 处理 `add Document` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    long addDocument(Long userId, DocumentAddRequest request);

    /**
     * 更新 `update Document` 对应内容。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    boolean updateDocument(Long userId, DocumentUpdateRequest request);

    /**
     * 删除 `delete Document` 对应内容。
     *
     * @param userId userId 参数。
     * @param id id 参数。
     * @return 返回处理结果。
     */
    boolean deleteDocument(Long userId, Long id);

    /**
     * 获取 `get Document By Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param id id 参数。
     * @return 返回处理结果。
     */
    com.shenchen.cloudcoldagent.model.entity.Document getDocumentById(Long userId, Long id);

    /**
     * 查询 `list By Knowledge Id` 对应集合。
     *
     * @param userId userId 参数。
     * @param knowledgeId knowledgeId 参数。
     * @return 返回处理结果。
     */
    List<com.shenchen.cloudcoldagent.model.entity.Document> listByKnowledgeId(Long userId, Long knowledgeId);

    /**
     * 处理 `page By User Id` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    Page<com.shenchen.cloudcoldagent.model.entity.Document> pageByUserId(Long userId, DocumentQueryRequest request);

    /**
     * 获取 `get Query Wrapper` 对应结果。
     *
     * @param userId userId 参数。
     * @param request request 参数。
     * @return 返回处理结果。
     */
    QueryWrapper getQueryWrapper(Long userId, DocumentQueryRequest request);

    /**
     * 获取 `get Document VO` 对应结果。
     *
     * @param document document 参数。
     * @return 返回处理结果。
     */
    DocumentVO getDocumentVO(com.shenchen.cloudcoldagent.model.entity.Document document);

    /**
     * 获取 `get Document VO List` 对应结果。
     *
     * @param documents documents 参数。
     * @return 返回处理结果。
     */
    List<DocumentVO> getDocumentVOList(List<com.shenchen.cloudcoldagent.model.entity.Document> documents);
}
