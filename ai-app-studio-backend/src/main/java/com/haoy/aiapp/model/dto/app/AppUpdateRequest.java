package com.haoy.aiapp.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用更新请求（用户）
 * 用户目前只支持修改应用名称
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    private static final long serialVersionUID = 1L;
}
