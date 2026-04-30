package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.chat.ChatConversationDeleteRequest;
import com.shenchen.cloudcoldagent.model.dto.chat.ChatConversationSkillUpdateRequest;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 会话控制层
 */
@RestController
@RequestMapping("/chatConversation")
public class ChatConversationController {

    private final ChatConversationService chatConversationService;

    private final UserService userService;

    public ChatConversationController(ChatConversationService chatConversationService,
                                      UserService userService) {
        this.chatConversationService = chatConversationService;
        this.userService = userService;
    }

    /**
     * 新建会话
     */
    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<String> createConversation(HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        String createdConversationId = chatConversationService.createConversation(loginUser.getId());
        return ResultUtils.success(createdConversationId);
    }

    /**
     * 查询当前用户会话列表
     */
    @GetMapping("/list/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<ChatConversation>> listMyConversations(HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(chatConversationService.listByUserId(loginUser.getId()));
    }

    /**
     * 查询会话详情
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<ChatConversation> getConversation(@RequestParam String conversationId,
                                                          HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(conversationId == null || conversationId.isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(chatConversationService.getByConversationId(loginUser.getId(), conversationId));
    }

    /**
     * 更新会话绑定的 skills
     */
    @PostMapping("/update/skills")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateConversationSkills(@RequestBody ChatConversationSkillUpdateRequest request,
                                                          HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getConversationId() == null || request.getConversationId().isBlank(),
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        chatConversationService.updateConversationSkills(loginUser.getId(), request.getConversationId(), request.getSelectedSkills());
        return ResultUtils.success(true);
    }

    /**
     * 删除会话（级联删除会话消息）
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteConversation(@RequestBody ChatConversationDeleteRequest request,
                                                    HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null || request.getConversationId() == null || request.getConversationId().isBlank(),
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        boolean result = chatConversationService.deleteConversation(loginUser.getId(), request.getConversationId());
        return ResultUtils.success(result);
    }
}
