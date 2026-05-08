package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.DeleteRequest;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.utils.ThrowUtils;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.ChatMemoryHistoryVO;
import com.shenchen.cloudcoldagent.service.ChatMemoryHistoryService;
import com.shenchen.cloudcoldagent.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 聊天记忆控制层
 */
@RestController
@RequestMapping("/chatMemory/history")
public class ChatMemoryHistoryController {

    private final ChatMemoryHistoryService chatMemoryHistoryService;

    private final UserService userService;

    /**
     * 创建 `ChatMemoryHistoryController` 实例。
     *
     * @param chatMemoryHistoryService chatMemoryHistoryService 参数。
     * @param userService userService 参数。
     */
    public ChatMemoryHistoryController(ChatMemoryHistoryService chatMemoryHistoryService,
                                       UserService userService) {
        this.chatMemoryHistoryService = chatMemoryHistoryService;
        this.userService = userService;
    }

    /**
     * 根据 conversationId 查询历史记录
     */
    @GetMapping("/list/conversation")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<ChatMemoryHistoryVO>> listByConversationId(@RequestParam String conversationId,
                                                                        HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(chatMemoryHistoryService.listByConversationId(loginUser.getId(), conversationId));
    }

    /**
     * 根据 userId 查询该用户所有会话 id
     */
    @GetMapping({"/list/user", "/list/user/conversations"})
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<String>> listConversationIdsByUserId(@RequestParam Long userId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(userId == null || !userId.equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR);
        return ResultUtils.success(chatMemoryHistoryService.listConversationIdsByUserId(userId));
    }

    /**
     * 根据历史记录 id 删除该条历史记录
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> deleteById(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(chatMemoryHistoryService.deleteByHistoryId(loginUser.getId(), deleteRequest.getId()));
    }
}
