package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.usermemory.UserMemoryRenamePetRequest;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserLongTermMemoryVO;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserPetStateVO;
import com.shenchen.cloudcoldagent.service.UserService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 长期记忆 / 宠物记忆控制层，提供宠物状态、记忆列表和人工维护接口。
 */
@RestController
@RequestMapping("/userMemory")
public class UserLongTermMemoryController {

    private final UserLongTermMemoryService userLongTermMemoryService;
    private final UserService userService;

    /**
     * 注入长期记忆接口所需的业务服务。
     *
     * @param userLongTermMemoryService 长期记忆业务服务。
     * @param userService 用户业务服务。
     */
    public UserLongTermMemoryController(UserLongTermMemoryService userLongTermMemoryService,
                                        UserService userService) {
        this.userLongTermMemoryService = userLongTermMemoryService;
        this.userService = userService;
    }

    /**
     * 查询当前用户的宠物记忆状态。
     *
     * @param request 当前 HTTP 请求。
     * @return 宠物状态视图对象。
     */
    @GetMapping("/pet/state")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<UserPetStateVO> getPetState(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.getPetState(loginUser.getId()));
    }

    /**
     * 查询当前用户的长期记忆列表。
     *
     * @param request 当前 HTTP 请求。
     * @return 长期记忆列表。
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<UserLongTermMemoryVO>> list(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.listMemories(loginUser.getId()));
    }

    /**
     * 手动触发当前用户的长期记忆重建流程。
     *
     * @param request 当前 HTTP 请求。
     * @return 是否触发成功。
     */
    @PostMapping("/rebuild")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> rebuild(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.triggerManualRebuild(loginUser.getId()));
    }

    /**
     * 修改当前用户宠物记忆的名称。
     *
     * @param body 改名请求体。
     * @param request 当前 HTTP 请求。
     * @return 是否修改成功。
     */
    @PostMapping("/rename")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> rename(@RequestBody UserMemoryRenamePetRequest body,
                                        HttpServletRequest request) {
        ThrowUtils.throwIf(body == null || body.getPetName() == null || body.getPetName().isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.renamePet(loginUser.getId(), body.getPetName()));
    }

    /**
     * 删除当前用户的一条长期记忆。
     *
     * @param memoryId 记忆 id。
     * @param request 当前 HTTP 请求。
     * @return 是否删除成功。
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> delete(@RequestParam String memoryId,
                                        HttpServletRequest request) {
        ThrowUtils.throwIf(memoryId == null || memoryId.isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.deleteMemory(loginUser.getId(), memoryId));
    }
}
