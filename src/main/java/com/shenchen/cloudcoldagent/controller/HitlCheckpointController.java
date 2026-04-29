package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.hitl.HitlCheckpointCreateRequest;
import com.shenchen.cloudcoldagent.model.dto.hitl.HitlCheckpointResolveRequest;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import com.shenchen.cloudcoldagent.service.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hitl/checkpoint")
public class HitlCheckpointController {

    private final HitlCheckpointService hitlCheckpointService;
    private final UserService userService;

    public HitlCheckpointController(HitlCheckpointService hitlCheckpointService,
                                    UserService userService) {
        this.hitlCheckpointService = hitlCheckpointService;
        this.userService = userService;
    }


    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> getByInterruptId(@RequestParam String interruptId,
                                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(interruptId == null || interruptId.isBlank(), ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.getByInterruptId(interruptId));
    }

    @GetMapping("/latest")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> getLatestPending(@RequestParam String conversationId,
                                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(conversationId == null || conversationId.isBlank(), ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.getLatestPendingByConversationId(conversationId));
    }

    @PostMapping("/resolve")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<HitlCheckpointVO> resolve(@RequestBody HitlCheckpointResolveRequest request,
                                                  HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getInterruptId() == null || request.getInterruptId().isBlank(),
                ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(hitlCheckpointService.resolveCheckpoint(
                request.getInterruptId(),
                request.getFeedbacks()
        ));
    }
}
