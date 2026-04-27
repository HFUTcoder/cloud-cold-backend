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
import com.shenchen.cloudcoldagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/document")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private UserService userService;

    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Long> createDocument(@RequestBody DocumentAddRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.addDocument(loginUser.getId(), request));
    }

    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<DocumentVO> getDocument(long id, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.getDocumentVO(documentService.getDocumentById(loginUser.getId(), id)));
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
