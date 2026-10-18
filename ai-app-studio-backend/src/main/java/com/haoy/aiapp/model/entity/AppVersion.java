package com.haoy.aiapp.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用版本 实体类。
 * <p>
 * 一行 = 一次成功生成留下的产物快照。产物本体不进数据库（可能是几百 MB），
 * 这里只存相对路径，好处是版本根目录可以随时外置/迁移到别的磁盘。
 * <p>
 * <b>为什么要有 contentHash：</b>重新生成时模型有时会吐出一模一样的内容，
 * 如果每次生成都无脑记一个版本，列表里很快就全是「没差别」的条目，
 * 回滚时也没法一眼看出哪次是真正的改动。摘要相同就不记新版本。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_version")
public class AppVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用 id
     */
    @Column("appId")
    private Long appId;

    /**
     * 版本号（同一应用内单调递增，从 1 开始）
     */
    @Column("versionNo")
    private Integer versionNo;

    /**
     * 生成该版本时的代码类型（html / multi_file / vue_project）
     */
    @Column("codeGenType")
    private String codeGenType;

    /**
     * 产物内容摘要（sha256），用于跳过「无实质变化」的版本
     */
    @Column("contentHash")
    private String contentHash;

    /**
     * 快照文件数
     */
    @Column("fileCount")
    private Integer fileCount;

    /**
     * 快照体积（字节）
     */
    @Column("sizeBytes")
    private Long sizeBytes;

    /**
     * 快照目录（相对版本根目录），如 {@code 1234/3}
     */
    @Column("storagePath")
    private String storagePath;

    /**
     * 来源：CHAT（对话生成）/ WORKFLOW（AI 工作流）/ ROLLBACK（回滚时另存一份）
     */
    private String source;

    /**
     * 触发本次生成的用户消息（截断到 500 字，仅作说明用）
     */
    private String prompt;

    /**
     * 是否当前生效版本
     */
    @Column("isCurrent")
    private Integer isCurrent;

    /**
     * 操作人 id
     */
    @Column("userId")
    private Long userId;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
