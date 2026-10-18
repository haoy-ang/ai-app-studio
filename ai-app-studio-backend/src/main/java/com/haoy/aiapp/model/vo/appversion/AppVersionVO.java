package com.haoy.aiapp.model.vo.appversion;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用版本 视图对象
 * <p>
 * 只暴露前端画列表需要的东西：不返回 {@code storagePath}（磁盘路径属于实现细节，
 * 暴露出去等于把服务器目录结构告诉客户端），也不返回 {@code contentHash}（对用户没有意义）。
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppVersionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 版本 id
     */
    private Long id;

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 版本号（展示成 v1 / v2，比 id 好认）
     */
    private Integer versionNo;

    /**
     * 生成类型
     */
    private String codeGenType;

    /**
     * 文件数
     */
    private Integer fileCount;

    /**
     * 体积（字节）
     */
    private Long sizeBytes;

    /**
     * 来源：chat / workflow / rollback
     */
    private String source;

    /**
     * 触发本次生成的用户消息（可能为空）
     */
    private String prompt;

    /**
     * 是否当前生效版本
     * <p>
     * 前端拿到的是 boolean，不用再拿 Integer 去比 1 —— 少一处「== 1 还是 != 0」的歧义。
     */
    private Boolean current;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
