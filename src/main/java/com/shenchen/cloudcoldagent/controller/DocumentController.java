package com.shenchen.cloudcoldagent.controller;

import com.mybatisflex.core.paginate.Page;
import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.DeleteRequest;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.DocumentVO;
import com.shenchen.cloudcoldagent.service.DocumentService;
import com.shenchen.cloudcoldagent.service.KnowledgeDocumentIngestionService;
import com.shenchen.cloudcoldagent.service.MinioService;
import com.shenchen.cloudcoldagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    private final UserService userService;

    private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;

    private final MinioService minioService;

    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Long> createDocument(@RequestBody DocumentAddRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.addDocument(loginUser.getId(), request));
    }

    @PostMapping("/upload")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<DocumentVO> uploadDocument(@RequestParam Long knowledgeId,
                                                   @RequestParam("file") MultipartFile file,
                                                   HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeDocumentIngestionService.uploadDocument(loginUser.getId(), knowledgeId, file));
    }

    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<DocumentVO> getDocument(long id, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.getDocumentVO(documentService.getDocumentById(loginUser.getId(), id)));
    }

    @GetMapping("/preview-url")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<String> getDocumentPreviewUrl(@RequestParam Long id, HttpServletRequest httpServletRequest) throws Exception {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        com.shenchen.cloudcoldagent.model.entity.Document document = documentService.getDocumentById(loginUser.getId(), id);
        ThrowUtils.throwIf(document.getObjectName() == null || document.getObjectName().isBlank(),
                ErrorCode.OPERATION_ERROR, "当前文档缺少可预览的原文件");
        return ResultUtils.success(minioService.getPresignedUrl(document.getObjectName()));
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateDocument(@RequestBody DocumentUpdateRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.updateDocument(loginUser.getId(), request));
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteDocument(@RequestBody DeleteRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.deleteDocument(loginUser.getId(), request.getId()));
    }

    @PostMapping("/list/page/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Page<DocumentVO>> listMyDocumentByPage(@RequestBody DocumentQueryRequest request,
                                                               HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Page<com.shenchen.cloudcoldagent.model.entity.Document> documentPage = documentService.pageByUserId(loginUser.getId(), request);
        Page<DocumentVO> documentVOPage = new Page<>(documentPage.getPageNumber(), documentPage.getPageSize(), documentPage.getTotalRow());
        documentVOPage.setRecords(documentService.getDocumentVOList(documentPage.getRecords()));
        return ResultUtils.success(documentVOPage);
    }

    @GetMapping("/list/by/knowledge")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<DocumentVO>> listByKnowledgeId(@RequestParam Long knowledgeId, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.getDocumentVOList(documentService.listByKnowledgeId(loginUser.getId(), knowledgeId)));
    }
}
