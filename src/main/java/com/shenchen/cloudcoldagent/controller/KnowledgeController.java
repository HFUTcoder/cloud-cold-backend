package com.shenchen.cloudcoldagent.controller;

import com.mybatisflex.core.paginate.Page;
import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.DeleteRequest;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeAddRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeHybridSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeMetadataSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeQueryRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeScalarSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeUpdateRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeVectorSearchRequest;
import com.shenchen.cloudcoldagent.model.dto.knowledge.KnowledgeWriteRequest;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.model.entity.Knowledge;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.KnowledgeVO;
import com.shenchen.cloudcoldagent.service.KnowledgeService;
import com.shenchen.cloudcoldagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private UserService userService;

    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Long> createKnowledge(@RequestBody KnowledgeAddRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.createKnowledge(loginUser.getId(), request));
    }

    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<KnowledgeVO> getKnowledge(long id, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        Knowledge knowledge = knowledgeService.getKnowledgeById(loginUser.getId(), id);
        return ResultUtils.success(knowledgeService.getKnowledgeVO(knowledge));
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateKnowledge(@RequestBody KnowledgeUpdateRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.updateKnowledge(loginUser.getId(), request));
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteKnowledge(@RequestBody DeleteRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(knowledgeService.deleteKnowledge(loginUser.getId(), request.getId()));
    }

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
     * 1. 文档写入
     */
    @PostMapping("/write")
    public List<EsDocumentChunk> write(@RequestBody KnowledgeWriteRequest request) throws Exception {
        return knowledgeService.add(request.getFilePath());
    }

    /**
     * 2. 文档标量检索
     */
    @PostMapping("/scalar-search")
    public List<EsDocumentChunk> scalarSearch(@RequestBody KnowledgeScalarSearchRequest request)
            throws Exception {
        if (request.getSize() == null && request.getUseSmartAnalyzer() == null) {
            return knowledgeService.scalarSearch(request.getQuery());
        }
        return knowledgeService.scalarSearch(request.getQuery(),
                request.getSize() == null ? 5 : request.getSize(),
                Boolean.TRUE.equals(request.getUseSmartAnalyzer()));
    }

    /**
     * 3. 文档元数据检索
     */
    @PostMapping("/metadata-search")
    public List<EsDocumentChunk> metadataSearch(@RequestBody KnowledgeMetadataSearchRequest request)
            throws Exception {
        if (request.getSize() == null) {
            return knowledgeService.metadataSearch(request.getMetadataFilters());
        }
        return knowledgeService.metadataSearch(request.getMetadataFilters(), request.getSize());
    }

    /**
     * 4. 文档相似度检索
     */
    @PostMapping("/vector-search")
    public List<EsDocumentChunk> vectorSearch(@RequestBody KnowledgeVectorSearchRequest request)
            throws Exception {
        if (request.getTopK() == null && request.getSimilarityThreshold() == null && request.getFilterExpression() == null) {
            return knowledgeService.vectorSearch(request.getQuery());
        }
        return knowledgeService.vectorSearch(request.getQuery(),
                request.getTopK() == null ? 5 : request.getTopK(),
                request.getSimilarityThreshold() == null ? 0.0d : request.getSimilarityThreshold(),
                request.getFilterExpression());
    }

    /**
     * 5. 文档混合检索
     */
    @PostMapping("/hybrid-search")
    public List<EsDocumentChunk> hybridSearch(@RequestBody KnowledgeHybridSearchRequest request)
            throws Exception {
        if (request.getKeywordSize() == null && request.getUseSmartAnalyzer() == null && request.getVectorTopK() == null
                && request.getSimilarityThreshold() == null && request.getFilterExpression() == null) {
            return knowledgeService.hybridSearch(request.getQuery());
        }
        return knowledgeService.hybridSearch(request.getQuery(),
                request.getKeywordSize() == null ? 5 : request.getKeywordSize(),
                Boolean.TRUE.equals(request.getUseSmartAnalyzer()),
                request.getVectorTopK() == null ? 5 : request.getVectorTopK(),
                request.getSimilarityThreshold() == null ? 0.0d : request.getSimilarityThreshold(),
                request.getFilterExpression());
    }
}
