package com.haoy.aiapp.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用部署请求
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppDeployRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    private static final long serialVersionUID = 1L;
}
