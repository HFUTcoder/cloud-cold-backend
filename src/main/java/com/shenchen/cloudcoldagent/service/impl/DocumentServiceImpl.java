package com.shenchen.cloudcoldagent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.mapper.DocumentMapper;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import com.shenchen.cloudcoldagent.service.DocumentService;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, com.shenchen.cloudcoldagent.model.entity.Document>
        implements DocumentService {

    private final KnowledgeService knowledgeService;

    public DocumentServiceImpl(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public long addDocument(Long userId, DocumentAddRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getDocumentName() == null || request.getDocumentName().isBlank(),
                ErrorCode.PARAMS_ERROR, "文档名称不能为空");

        knowledgeService.getKnowledgeById(userId, request.getKnowledgeId());

        com.shenchen.cloudcoldagent.model.entity.Document document = new com.shenchen.cloudcoldagent.model.entity.Document();
        BeanUtil.copyProperties(request, document);
        document.setUserId(userId);
        boolean result = this.save(document);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建文档失败");
        knowledgeService.refreshKnowledgeStats(userId, request.getKnowledgeId());
        return document.getId();
    }

    @Override
    public boolean updateDocument(Long userId, DocumentUpdateRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);

        com.shenchen.cloudcoldagent.model.entity.Document existing = getDocumentById(userId, request.getId());
        Long oldKnowledgeId = existing.getKnowledgeId();

        if (request.getKnowledgeId() != null) {
            knowledgeService.getKnowledgeById(userId, request.getKnowledgeId());
            existing.setKnowledgeId(request.getKnowledgeId());
        }
        existing.setDocumentName(request.getDocumentName());
        existing.setDocumentUrl(request.getDocumentUrl());
        existing.setObjectName(request.getObjectName());
        existing.setDocumentSource(request.getDocumentSource());
        existing.setFileType(request.getFileType());
        existing.setContentType(request.getContentType());
        existing.setFileSize(request.getFileSize());
        existing.setIndexStatus(request.getIndexStatus());
        boolean result = this.updateById(existing);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新文档失败");
        knowledgeService.refreshKnowledgeStats(userId, oldKnowledgeId);
        knowledgeService.refreshKnowledgeStats(userId, existing.getKnowledgeId());
        return true;
    }

    @Override
    public boolean deleteDocument(Long userId, Long id) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);

        com.shenchen.cloudcoldagent.model.entity.Document document = getDocumentById(userId, id);
        if (document.getDocumentSource() != null && !document.getDocumentSource().isBlank()) {
            try {
                knowledgeService.deleteBySource(document.getDocumentSource());
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除文档对应的索引内容失败");
            }
        }
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除文档失败");
        knowledgeService.refreshKnowledgeStats(userId, document.getKnowledgeId());
        return true;
    }

    @Override
    public com.shenchen.cloudcoldagent.model.entity.Document getDocumentById(Long userId, Long id) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        com.shenchen.cloudcoldagent.model.entity.Document document = this.mapper.selectOneByQuery(
                QueryWrapper.create()
                        .eq("id", id)
                        .eq("userId", userId)
        );
        ThrowUtils.throwIf(document == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        return document;
    }

    @Override
    public List<com.shenchen.cloudcoldagent.model.entity.Document> listByKnowledgeId(Long userId, Long knowledgeId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);
        knowledgeService.getKnowledgeById(userId, knowledgeId);
        return this.mapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("knowledgeId", knowledgeId)
                        .orderBy("createTime", false)
        );
    }

    @Override
    public Page<com.shenchen.cloudcoldagent.model.entity.Document> pageByUserId(Long userId, DocumentQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return this.page(Page.of(request.getPageNum(), request.getPageSize()), getQueryWrapper(userId, request));
    }

    @Override
    public QueryWrapper getQueryWrapper(Long userId, DocumentQueryRequest request) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return QueryWrapper.create()
                .eq("userId", userId)
                .eq("id", request.getId())
                .eq("knowledgeId", request.getKnowledgeId())
                .like("documentName", request.getDocumentName())
                .eq("fileType", request.getFileType())
                .eq("indexStatus", request.getIndexStatus())
                .orderBy(request.getSortField(), "ascend".equals(request.getSortOrder()));
    }

    @Override
    public DocumentVO getDocumentVO(com.shenchen.cloudcoldagent.model.entity.Document document) {
        if (document == null) {
            return null;
        }
        DocumentVO documentVO = new DocumentVO();
        BeanUtil.copyProperties(document, documentVO);
        return documentVO;
    }

    @Override
    public List<DocumentVO> getDocumentVOList(List<com.shenchen.cloudcoldagent.model.entity.Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        return documents.stream().map(this::getDocumentVO).toList();
    }
}
