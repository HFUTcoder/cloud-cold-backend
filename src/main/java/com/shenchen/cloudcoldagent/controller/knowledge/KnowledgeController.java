package com.shenchen.cloudcoldagent.controller.knowledge;

import com.mybatisflex.core.paginate.Page;
import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.DeleteRequest;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeHybridSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeMetadataSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeScalarSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeVectorSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeWriteRequest;
import com.shenchen.cloudcoldagent.model.entity.knowledge.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.knowledge.Knowledge;
import com.shenchen.cloudcoldagent.model.entity.user.User;
import com.shenchen.cloudcoldagent.model.vo.knowledge.KnowledgeVO;
import com.shenchen.cloudcoldagent.service.knowledge.KnowledgeService;
import com.shenchen.cloudcoldagent.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库控制层，负责知识库管理以及调试/验证用的多种检索接口。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    private final UserService userService;

    /**
     * 注入知识库接口所需的业务服务。
     *
     * @param knowledgeService 知识库业务服务。
     * @param userService 用户业务服务。
     */
    public KnowledgeController(KnowledgeService knowledgeService,
                               UserService userService) {
        this.knowledgeService = knowledgeService;
        this.userService = userService;
    }

    /**
     * 为当前用户创建一个新的知识库。
     *
     * @param request 创建请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 新知识库 id。
     */
    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Long> createKnowledge(@RequestBody KnowledgeAddRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.createKnowledge(loginUser.getId(), request));
    }

    /**
     * 查询指定知识库详情。
     *
     * @param id 知识库 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 知识库详情。
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<KnowledgeVO> getKnowledge(long id, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Knowledge knowledge = knowledgeService.getKnowledgeById(loginUser.getId(), id);
        return ResultUtils.success(knowledgeService.getKnowledgeVO(knowledge));
    }

    /**
     * 更新当前用户名下的知识库信息。
     *
     * @param request 更新请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 是否更新成功。
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateKnowledge(@RequestBody KnowledgeUpdateRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.updateKnowledge(loginUser.getId(), request));
    }

    /**
     * 删除当前用户名下的知识库。
     *
     * @param request 删除请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 是否删除成功。
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteKnowledge(@RequestBody DeleteRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.deleteKnowledge(loginUser.getId(), request.getId()));
    }

    /**
     * 分页查询当前用户创建的知识库列表。
     *
     * @param request 分页查询条件。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 当前用户知识库分页结果。
     */
    @PostMapping("/list/page/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Page<KnowledgeVO>> listMyKnowledgeByPage(@RequestBody KnowledgeQueryRequest request,
                                                                 HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Page<Knowledge> knowledgePage = knowledgeService.pageByUserId(loginUser.getId(), request);
        Page<KnowledgeVO> knowledgeVOPage = new Page<>(knowledgePage.getPageNumber(), knowledgePage.getPageSize(), knowledgePage.getTotalRow());
        knowledgeVOPage.setRecords(knowledgeService.getKnowledgeVOList(knowledgePage.getRecords()));
        return ResultUtils.success(knowledgeVOPage);
    }

    /**
     * 将本地文档写入知识库索引，主要用于调试或离线验证。
     */
    @PostMapping("/write")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<EsDocumentChunk>> write(@RequestBody KnowledgeWriteRequest request,
                                                     HttpServletRequest httpServletRequest) throws Exception {
        ThrowUtils.throwIf(request == null || request.getFilePath() == null || request.getFilePath().isBlank(),
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.add(request.getFilePath(), loginUser.getId(), null));
    }

    /**
     * 执行基于关键词的标量检索。
     */
    @PostMapping("/scalar-search")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<EsDocumentChunk>> scalarSearch(@RequestBody KnowledgeScalarSearchRequest request,
                                                            HttpServletRequest httpServletRequest)
            throws Exception {
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        User loginUser = userService.getLoginUser(httpServletRequest);
        if (request.getSize() == null && request.getUseSmartAnalyzer() == null) {
            return ResultUtils.success(knowledgeService.scalarSearch(loginUser.getId(), request.getKnowledgeId(),
                    request.getQuery()));
        }
        return ResultUtils.success(knowledgeService.scalarSearch(loginUser.getId(), request.getKnowledgeId(),
                request.getQuery(),
                request.getSize() == null ? 5 : request.getSize(),
                Boolean.TRUE.equals(request.getUseSmartAnalyzer())));
    }

    /**
     * 执行基于元数据过滤条件的检索。
     */
    @PostMapping("/metadata-search")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<EsDocumentChunk>> metadataSearch(@RequestBody KnowledgeMetadataSearchRequest request,
                                                              HttpServletRequest httpServletRequest)
            throws Exception {
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        User loginUser = userService.getLoginUser(httpServletRequest);
        if (request.getSize() == null) {
            return ResultUtils.success(knowledgeService.metadataSearch(loginUser.getId(), request.getKnowledgeId(),
                    request.getMetadataFilters()));
        }
        return ResultUtils.success(knowledgeService.metadataSearch(loginUser.getId(), request.getKnowledgeId(),
                request.getMetadataFilters(), request.getSize()));
    }

    /**
     * 执行基于向量相似度的语义检索。
     */
    @PostMapping("/vector-search")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<EsDocumentChunk>> vectorSearch(@RequestBody KnowledgeVectorSearchRequest request,
                                                            HttpServletRequest httpServletRequest)
            throws Exception {
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        User loginUser = userService.getLoginUser(httpServletRequest);
        if (request.getTopK() == null && request.getSimilarityThreshold() == null) {
            return ResultUtils.success(knowledgeService.vectorSearch(loginUser.getId(), request.getKnowledgeId(),
                    request.getQuery()));
        }
        return ResultUtils.success(knowledgeService.vectorSearch(loginUser.getId(), request.getKnowledgeId(),
                request.getQuery(),
                request.getTopK() == null ? 5 : request.getTopK(),
                request.getSimilarityThreshold() == null ? 0.0d : request.getSimilarityThreshold()));
    }

    /**
     * 执行融合关键词检索和向量检索的混合检索。
     */
    @PostMapping("/hybrid-search")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<EsDocumentChunk>> hybridSearch(@RequestBody KnowledgeHybridSearchRequest request,
                                                            HttpServletRequest httpServletRequest)
            throws Exception {
        ThrowUtils.throwIf(request == null || request.getKnowledgeId() == null || request.getKnowledgeId() <= 0,
                ErrorCode.PARAMS_ERROR, "知识库 id 非法");
        User loginUser = userService.getLoginUser(httpServletRequest);
        if (request.getKeywordSize() == null && request.getUseSmartAnalyzer() == null && request.getVectorTopK() == null
                && request.getSimilarityThreshold() == null) {
            return ResultUtils.success(knowledgeService.hybridSearch(loginUser.getId(), request.getKnowledgeId(),
                    request.getQuery()));
        }
        return ResultUtils.success(knowledgeService.hybridSearch(loginUser.getId(), request.getKnowledgeId(),
                request.getQuery(),
                request.getKeywordSize() == null ? 5 : request.getKeywordSize(),
                Boolean.TRUE.equals(request.getUseSmartAnalyzer()),
                request.getVectorTopK() == null ? 5 : request.getVectorTopK(),
                request.getSimilarityThreshold() == null ? 0.5d : request.getSimilarityThreshold()));
    }
}
