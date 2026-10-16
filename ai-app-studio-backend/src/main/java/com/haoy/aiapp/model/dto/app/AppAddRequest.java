package com.haoy.aiapp.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用创建请求
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 应用名称（可选）
     * <p>
     * 不传则由后端取 initPrompt 前 12 位兜底。
     */
    private String appName;

    /**
     * 代码生成类型（可选）
     * <p>
     * 取值为 {@code html / multi_file / vue_project}；不传则由 AI 智能路由判断。
     * ⚠️ 传入非法值会被直接拒绝（参数错误），不会静默落库。
     */
    private String codeGenType;

    private static final long serialVersionUID = 1L;
}
