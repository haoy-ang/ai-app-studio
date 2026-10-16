package com.haoy.aiapp.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户个人资料更新请求（用户自助修改自己）
 * <p>
 * ⚠️ 刻意<b>不含</b> id 与 userRole 两个字段：改的是哪个用户，只由后端从 Session 里取。
 * 如果把 id 放进请求体，前端改个包就能改别人的资料；如果放进 userRole，
 * 那就是一条现成的提权后门。从 DTO 层面就不给这个可能，后续谁改代码都不容易踩。
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class UserProfileUpdateRequest implements Serializable {

    /**
     * 用户昵称（必填，1~20 字）
     */
    private String userName;

    /**
     * 用户头像地址（可选）
     * <p>
     * 传 null 或空串表示「不修改头像」；传值时必须是 http/https 链接。
     */
    private String userAvatar;

    /**
     * 用户简介（可选，≤200 字）
     * <p>
     * 传 null 表示「不修改」；传空串表示「清空简介」—— 两者语义不同，别混。
     */
    private String userProfile;

    private static final long serialVersionUID = 1L;
}
