package com.haoy.aiapp.model.vo.app;

import com.haoy.aiapp.model.vo.user.AppUserVO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用视图对象（包含创建用户信息）
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppVO implements Serializable {

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
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 部署标识
     */
    private String deployKey;

    /**
     * 部署时间
     */
    private LocalDateTime deployedTime;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建用户信息（公开场景，不含登录账号）
     */
    private AppUserVO user;

    /**
     * 代码是否已经生成过（前端据此决定「展示预览」还是「提示还没生成」）
     * <p>
     * 为什么不能靠前端自己判断：前端只看得到对话历史条数，而「历史非空」和
     * 「代码目录真的存在」是两件事 —— 生成失败时历史里有消息但目录是空的，
     * 只按历史判断会让用户看到一句「网站文件生成完成后将在这里展示」，
     * 误以为白生成了。由后端给出真实答案最省事。
     */
    private Boolean codeReady;

    private static final long serialVersionUID = 1L;
}
