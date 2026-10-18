package com.haoy.aiapp.controller.user;

import com.haoy.aiapp.annotation.AuthCheck;
import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.DeleteRequest;
import com.haoy.aiapp.common.ResultUtils;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.model.dto.user.UserAddRequest;
import com.haoy.aiapp.model.dto.user.UserLoginRequest;
import com.haoy.aiapp.model.dto.user.UserProfileUpdateRequest;
import com.haoy.aiapp.model.dto.user.UserQueryRequest;
import com.haoy.aiapp.model.dto.user.UserRegisterRequest;
import com.haoy.aiapp.model.dto.user.UserUpdateRequest;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.vo.user.LoginUserVO;
import com.haoy.aiapp.model.vo.user.UserVO;
import com.haoy.aiapp.service.user.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户 控制层。
 *
 * @author <a>程序员haoy</a>
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 创建用户（仅管理员）
     *
     * @param userAddRequest 用户新增请求
     * @return 新用户 id
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        // 默认密码 12345678
        final String DEFAULT_PASSWORD = "12345678";
        String encryptPassword = userService.getEncryptPassword(DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     *
     * @param id 用户 id
     * @return 用户详情
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     * <p>
     * ⚠️ 注意这里是「自己查库」而不是调用 {@link #getUserById(long)}。
     * 虽然那个方法上标了 {@code @AuthCheck(mustRole = ADMIN)}，但 Spring AOP 对
     * <b>同类内部方法调用不生效</b>（走的是 this 引用，不经过代理），
     * 调用它等于把管理员校验静默绕过。本接口本来就该公开，所以直接把查询逻辑写出来，
     * 别留一个「看起来被保护、其实是裸奔」的调用。
     *
     * @param id 用户 id
     * @return 脱敏用户
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 删除用户（仅管理员）
     * <p>
     * 不是简单删一行：该用户名下的应用、对话历史、版本快照、部署产物都会一起删除。
     * 具体保护（不能删自己 / 必须留一个管理员 / 释放账号）见
     * {@link UserService#removeUser(Long, Long)}。
     * <p>
     * ⚠️ {@code @CacheEvict}：精选列表的 AppVO 里嵌了作者信息，删掉用户后
     * 他的应用也不在了，但缓存最长会残留 5 分钟（点进去是已删除的数据）。
     *
     * @param deleteRequest 删除请求
     * @param request       请求（用于取当前操作人）
     * @return 删除结果
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        // 先判 null 再判 <= 0：id 是 Long，直接拆箱遇到 null 会 NPE，变成 500 而不是「参数错误」
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "用户 ID 无效");
        User loginUser = userService.getLoginUser(request);
        boolean result = userService.removeUser(deleteRequest.getId(), loginUser.getId());
        return ResultUtils.success(result);
    }

    /**
     * 更新用户（仅管理员）
     *
     * @param userUpdateRequest 用户更新请求
     * @return 更新结果
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        ThrowUtils.throwIf(userUpdateRequest == null || userUpdateRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     * @return 分页脱敏用户列表
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = userQueryRequest.getPageNum();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(Page.of(pageNum, pageSize), userService.getQueryWrapper(userQueryRequest));
        // 数据脱敏
        Page<UserVO> userVOPage = new Page<>(pageNum, pageSize, userPage.getTotalRow());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求
     * @return 注册结果（新用户 id）
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        Long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求
     * @param request          HTTP 请求
     * @return 脱敏后的用户信息
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     *
     * @param request 请求对象
     * @return 当前登录用户（脱敏后的用户信息）
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户登出
     *
     * @param request 请求对象
     * @return 登出结果
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】更新自己的个人资料（昵称 / 头像 / 简介）
     * <p>
     * 这是普通用户唯一能改自己资料的入口。注意权限是「登录即可」，不需要管理员 ——
     * 但改的一定是自己：用户 id 从 Session 里取，请求体里根本没有 id 字段可传。
     * <p>
     * 返回最新的 LoginUserVO，前端拿到后直接刷新本地 store，省一次额外请求。
     *
     * @param userProfileUpdateRequest 资料更新请求
     * @param request                  请求对象
     * @return 更新后的登录用户信息（脱敏）
     */
    @PostMapping("/update/my")
    public BaseResponse<LoginUserVO> updateMyProfile(@RequestBody UserProfileUpdateRequest userProfileUpdateRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(userProfileUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        User updatedUser = userService.updateMyProfile(loginUser, userProfileUpdateRequest);
        return ResultUtils.success(userService.getLoginUserVO(updatedUser));
    }

    /**
     * 【用户】上传头像，返回可访问 URL
     * <p>
     * 只上传不落库：返回的 URL 由前端回填到资料表单，用户点「保存」时才写进 userAvatar。
     * 这样「换头像但最后点了取消」不会在库里留下一个已改的字段。
     *
     * @param file    头像文件（表单字段名必须是 file）
     * @param request 请求对象
     * @return 头像访问 URL
     */
    @PostMapping("/avatar/upload")
    public BaseResponse<String> uploadAvatar(@RequestPart("file") MultipartFile file,
                                            HttpServletRequest request) {
        // 先校验登录：未登录不允许调用上传，避免匿名用户往 COS 里灌文件
        User loginUser = userService.getLoginUser(request);
        String avatarUrl = userService.uploadAvatar(file, loginUser);
        return ResultUtils.success(avatarUrl);
    }
}