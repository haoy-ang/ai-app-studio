package com.haoy.aiapp.model.vo.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用作者视图对象（公开场景使用的用户信息）
 * <p>
 * 与 {@link UserVO} 的唯一区别是：<b>不含 userAccount</b>。
 * <p>
 * 为什么必须拆开：应用列表（{@code /app/good/list/page/vo}）和应用详情（{@code /app/get/vo}）
 * 都是<b>公开接口</b>，而 {@code AppVO.user} 会把作者信息一起带出去。
 * 如果这里用带 userAccount 的 {@link UserVO}，任何人拉一次精选列表就能枚举出
 * 全站所有用户的登录账号 —— 账号若是手机号/邮箱注册，就是实打实的隐私泄露。
 * <p>
 * 约定：<b>凡是要返回给非管理员的用户信息，一律用本类</b>；
 * {@link UserVO} 只在管理端（用户管理页）使用。
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppUserVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    private static final long serialVersionUID = 1L;
}
