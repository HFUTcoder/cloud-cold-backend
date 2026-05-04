package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.annotation.AuthCheck;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.constant.UserConstant;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.exception.ThrowUtils;
import com.shenchen.cloudcoldagent.model.dto.usermemory.UserMemoryRenamePetRequest;
import com.shenchen.cloudcoldagent.model.dto.usermemory.UserMemorySwitchRequest;
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

@RestController
@RequestMapping("/userMemory")
public class UserLongTermMemoryController {

    private final UserLongTermMemoryService userLongTermMemoryService;
    private final UserService userService;

    public UserLongTermMemoryController(UserLongTermMemoryService userLongTermMemoryService,
                                        UserService userService) {
        this.userLongTermMemoryService = userLongTermMemoryService;
        this.userService = userService;
    }

    @GetMapping("/pet/state")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<UserPetStateVO> getPetState(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.getPetState(loginUser.getId()));
    }

    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<List<UserLongTermMemoryVO>> list(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.listMemories(loginUser.getId()));
    }

    @PostMapping("/switch")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> switchEnabled(@RequestBody UserMemorySwitchRequest body,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(body == null || body.getEnabled() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.setEnabled(loginUser.getId(), body.getEnabled()));
    }

    @PostMapping("/rename")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> rename(@RequestBody UserMemoryRenamePetRequest body,
                                        HttpServletRequest request) {
        ThrowUtils.throwIf(body == null || body.getPetName() == null || body.getPetName().isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.renamePet(loginUser.getId(), body.getPetName()));
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> delete(@RequestParam String memoryId,
                                        HttpServletRequest request) {
        ThrowUtils.throwIf(memoryId == null || memoryId.isBlank(), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userLongTermMemoryService.deleteMemory(loginUser.getId(), memoryId));
    }
}
