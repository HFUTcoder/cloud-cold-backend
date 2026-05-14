package com.shenchen.cloudcoldagent.controller.hitl;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.hitl.HitlCheckpointResolveRequest;
import com.shenchen.cloudcoldagent.model.entity.user.User;
import com.shenchen.cloudcoldagent.model.vo.hitl.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HITL checkpoint 控制层，负责查询待审批中断点和提交人工审批结果。
 */
@RestController
@RequestMapping("/hitl/checkpoint")
public class HitlCheckpointController {

    private final HitlCheckpointService hitlCheckpointService;
    private final UserService userService;

    /**
     * 注入 HITL checkpoint 处理所需的业务服务。
     *
     * @param hitlCheckpointService HITL checkpoint 服务。
     * @param userService 用户业务服务。
     */
    public HitlCheckpointController(HitlCheckpointService hitlCheckpointService,
                                    UserService userService) {
        this.hitlCheckpointService = hitlCheckpointService;
        this.userService = userService;
    }


    /**
     * 根据 interruptId 查询某个 HITL 中断点的详情。
     *
     * @param interruptId 中断 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 中断点详情。
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> getByInterruptId(@RequestParam String interruptId,
                                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(interruptId == null || interruptId.isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.getByInterruptId(loginUser.getId(), interruptId));
    }

    /**
     * 查询某个会话最近一次仍处于待处理状态的 HITL checkpoint。
     *
     * @param conversationId 会话 id。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 最近一次待处理的中断点。
     */
    @GetMapping("/latest")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> getLatestPending(@RequestParam String conversationId,
                                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(conversationId == null || conversationId.isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.getLatestPendingByConversationId(loginUser.getId(), conversationId));
    }

    /**
     * 提交用户对待审批工具调用的反馈，并更新对应 checkpoint 状态。
     *
     * @param request 审批结果请求体。
     * @param httpServletRequest 当前 HTTP 请求。
     * @return 更新后的 checkpoint 详情。
     */
    @PostMapping("/resolve")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> resolve(@RequestBody HitlCheckpointResolveRequest request,
                                                  HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getInterruptId() == null || request.getInterruptId().isBlank(),
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.resolveCheckpoint(
                loginUser.getId(),
                request.getInterruptId(),
                request.getFeedbacks()
        ));
    }
}
