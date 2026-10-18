-- ============================================================
-- 增量升级：应用版本管理（E1）
--
-- 已建过库的环境执行本文件即可，不需要重建库：
--   mysql -uroot -p ai_app_studio < sql/upgrade-20261018-app-version.sql
--
-- 新建环境直接跑 create_table.sql（里面已经包含这张表）。
-- ============================================================

use ai_app_studio;

create table if not exists app_version
(
    id          bigint auto_increment comment 'id' primary key,
    appId       bigint                             not null comment '应用 id',
    versionNo   int                                not null comment '版本号（同一应用内递增，从 1 开始）',
    codeGenType varchar(64)                        not null comment '生成该版本时的代码类型',
    contentHash char(64)                           not null comment '产物内容摘要（sha256）',
    fileCount   int      default 0                 not null comment '快照文件数',
    sizeBytes   bigint   default 0                 not null comment '快照体积（字节）',
    storagePath varchar(512)                       not null comment '快照目录（相对版本根目录）',
    source      varchar(32)                        not null comment '来源：CHAT / WORKFLOW / ROLLBACK',
    prompt      varchar(512)                       null comment '触发本次生成的用户消息（截断）',
    isCurrent   tinyint  default 0                 not null comment '是否当前生效版本',
    userId      bigint                             null comment '操作人 id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_app_versionNo (appId, versionNo),
    INDEX idx_appId_isCurrent (appId, isCurrent)
) comment '应用版本' collate = utf8mb4_unicode_ci;
