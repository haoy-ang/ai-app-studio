package com.haoy.aiapp.service.user;

import com.haoy.aiapp.model.dto.user.UserProfileUpdateRequest;
import com.haoy.aiapp.model.dto.user.UserQueryRequest;
import com.haoy.aiapp.model.vo.user.AppUserVO;
import com.haoy.aiapp.model.vo.user.LoginUserVO;
import com.haoy.aiapp.model.vo.user.UserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.haoy.aiapp.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户 服务层。
 *
 * @author <a>程序员haoy</a>
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @param userAccount 用户账号
     * @param userPassword 用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    Long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 获取加密后的密码
     *
     * @param userPassword 原始密码
     * @return MD5 加盐密码
     */
    String getEncryptPassword(String userPassword);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户登录
     *
     * @param userAccount 用户账号
     * @param userPassword 用户密码
     * @param request 请求对象
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request 请求对象
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户退出登录
     *
     * @param request 请求对象
     * @return 是否退出成功
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取用户视图对象（脱敏返回）
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    UserVO getUserVO(User user);

    /**
     * 获取用户视图对象列表（脱敏返回）
     *
     * @param userList 用户实体列表
     * @return 用户视图对象列表
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 获取应用作者视图对象（公开场景使用，<b>不含登录账号</b>）
     * <p>
     * 与 {@link #getUserVO(User)} 的区别只有一个字段，但用途严格区分：
     * 凡是要返回给非管理员的用户信息都走这里，账号只在管理端可见。
     *
     * @param user 用户实体
     * @return 应用作者视图对象
     */
    AppUserVO getAppUserVO(User user);

    /**
     * 更新当前登录用户的个人资料（昵称 / 头像 / 简介）
     * <p>
     * ⚠️ 只允许改这三个字段：目标用户由 loginUser 决定，id 与 userRole 一律不碰。
     *
     * @param loginUser     当前登录用户
     * @param updateRequest 资料更新请求
     * @return 更新后的用户实体（供上层转 VO 返回给前端）
     */
    User updateMyProfile(User loginUser, UserProfileUpdateRequest updateRequest);

    /**
     * 上传用户头像到对象存储，返回可访问 URL（<b>不写库</b>，落库由 updateMyProfile 负责）
     *
     * @param file      头像文件
     * @param loginUser 当前登录用户
     * @return 头像访问 URL
     */
    String uploadAvatar(MultipartFile file, User loginUser);

    /**
     * 根据查询条件构造数据查询参数
     *
     * @param userQueryRequest 用户查询请求
     * @return 查询参数
     */
    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);
}