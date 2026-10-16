package com.haoy.aiapp.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用更新请求（管理员）
 * 管理员可修改应用名称、应用封面、优先级（优先级 99 表示精选）
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 优先级
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}
