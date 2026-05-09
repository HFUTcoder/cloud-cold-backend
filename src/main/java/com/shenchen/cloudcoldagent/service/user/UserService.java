package com.shenchen.cloudcoldagent.service.user;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.dto.user.UserQueryRequest;
import com.shenchen.cloudcoldagent.model.entity.User;
import com.shenchen.cloudcoldagent.model.vo.LoginUserVO;
import com.shenchen.cloudcoldagent.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 用户服务接口，定义注册、登录、登录态获取和用户视图转换等能力。
 */
public interface UserService extends IService<User> {

    /**
     * 注册新用户账号。
     *
     * @param userAccount 用户账号。
     * @param userPassword 用户密码。
     * @param checkPassword 确认密码。
     * @return 新用户 id。
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 将当前登录用户实体转换成脱敏后的登录视图对象。
     *
     * @param user 当前登录用户实体。
     * @return 脱敏后的登录用户视图。
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 执行用户登录并写入 Session 登录态。
     *
     * @param userAccount 用户账号。
     * @param userPassword 用户密码。
     * @param request 当前 HTTP 请求。
     * @return 登录成功后的用户视图。
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 读取当前请求对应的登录用户实体。
     *
     * @param request 当前 HTTP 请求。
     * @return 当前登录用户实体。
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 将用户实体转换成对外暴露的脱敏视图对象。
     *
     * @param user 用户实体。
     * @return 用户视图对象。
     */
    UserVO getUserVO(User user);

    /**
     * 批量将用户实体转换成视图对象列表。
     *
     * @param userList 用户实体列表。
     * @return 用户视图列表。
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 注销当前请求对应的登录态。
     *
     * @param request 当前 HTTP 请求。
     * @return 是否注销成功。
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 根据查询条件构建用户分页查询包装器。
     *
     * @param userQueryRequest 用户查询条件。
     * @return MyBatis-Flex 查询包装器。
     */
    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 对原始密码执行统一的加密处理。
     *
     * @param userPassword 原始密码。
     * @return 加密后的密码。
     */
    String getEncryptPassword(String userPassword);
}
