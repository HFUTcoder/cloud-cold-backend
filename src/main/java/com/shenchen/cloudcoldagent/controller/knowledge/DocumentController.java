package com.shenchen.cloudcoldagent.controller.knowledge;

import com.mybatisflex.core.paginate.Page;
import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.DeleteRequest;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentAddRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.document.DocumentUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.knowledge.Document;
import com.shenchen.cloudcoldagent.model.entity.user.User;
import com.shenchen.cloudcoldagent.model.vo.knowledge.DocumentVO;
import com.shenchen.cloudcoldagent.service.knowledge.DocumentService;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeDocumentIngestionService;
import com.shenchen.cloudcoldagent.service.storage.MinioService;
import com.shenchen.cloudcoldagent.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档控制层，负责文档管理、上传入库、预览和按知识库查询。
 */
@RestController
@RequestMapping("/document")
public class DocumentController {

    private final DocumentService documentService;

    private final UserService userService;

    private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;

    private final MinioService minioService;

    /**
     * 注入文档接口所需的业务服务。
     *
     * @param documentService 文档业务服务。
     * @param userService 用户业务服务。
     * @param knowledgeDocumentIngestionService 文档入库服务。
     * @param minioService MinIO 文件服务。
     */
    public DocumentController(DocumentService documentService,
                              UserService userService,
                              KnowledgeDocumentIngestionService knowledgeDocumentIngestionService,
                              MinioService minioService) {
        this.documentService = documentService;
        this.userService = userService;
        this.knowledgeDocumentIngestionService = knowledgeDocumentIngestionService;
        this.minioService = minioService;
    }

    /**
     * 创建一条文档记录。
     *
     * @param request 创建请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 新文档 id。
     */
    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Long> createDocument(@RequestBody DocumentAddRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.addDocument(loginUser.getId(), request));
    }

    /**
     * 上传 PDF 文档并同步完成知识库入库。
     *
     * @param knowledgeId 目标知识库 id。
     * @param file 上传文件。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 入库后的文档视图对象。
     */
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

    /**
     * 查询指定文档详情。
     *
     * @param id 文档 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 文档视图对象。
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<DocumentVO> getDocument(long id, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.getDocumentVO(documentService.getDocumentById(loginUser.getId(), id)));
    }

    /**
     * 获取文档原始文件的预签名预览地址。
     *
     * @param id 文档 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return MinIO 预签名 URL。
     * @throws Exception 生成预签名地址失败时抛出。
     */
    @GetMapping("/preview-url")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<String> getDocumentPreviewUrl(@RequestParam Long id, HttpServletRequest httpServletRequest) throws Exception {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Document document = documentService.getDocumentById(loginUser.getId(), id);
        ThrowUtils.throwIf(document.getObjectName() == null || document.getObjectName().isBlank(),
                ErrorCode.OPERATION_ERROR, "当前文档缺少可预览的原文件");
        return ResultUtils.success(minioService.getPresignedUrl(document.getObjectName()));
    }

    /**
     * 更新文档基础信息。
     *
     * @param request 更新请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 是否更新成功。
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateDocument(@RequestBody DocumentUpdateRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.updateDocument(loginUser.getId(), request));
    }

    /**
     * 删除文档及其关联入库信息。
     *
     * @param request 删除请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 是否删除成功。
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteDocument(@RequestBody DeleteRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.deleteDocument(loginUser.getId(), request.getId()));
    }

    /**
     * 分页查询当前用户的文档列表。
     *
     * @param request 分页查询条件。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 文档分页视图结果。
     */
    @PostMapping("/list/page/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Page<DocumentVO>> listMyDocumentByPage(@RequestBody DocumentQueryRequest request,
                                                               HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Page<Document> documentPage = documentService.pageByUserId(loginUser.getId(), request);
        Page<DocumentVO> documentVOPage = new Page<>(documentPage.getPageNumber(), documentPage.getPageSize(), documentPage.getTotalRow());
        documentVOPage.setRecords(documentService.getDocumentVOList(documentPage.getRecords()));
        return ResultUtils.success(documentVOPage);
    }

    /**
     * 查询某个知识库下的全部文档列表。
     *
     * @param knowledgeId 知识库 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 文档视图列表。
     */
    @GetMapping("/list/by/knowledge")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<DocumentVO>> listByKnowledgeId(@RequestParam Long knowledgeId, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(knowledgeId == null || knowledgeId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(documentService.getDocumentVOList(documentService.listByKnowledgeId(loginUser.getId(), knowledgeId)));
    }
}
