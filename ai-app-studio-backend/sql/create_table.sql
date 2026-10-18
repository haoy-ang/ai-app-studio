# 数据库初始化

-- 创建库
create database if not exists ai_app_studio;

-- 切换库
use ai_app_studio;

-- 用户表
-- 以下是建表语句

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;

-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;

-- 应用版本表（每生成一次打一个快照，可回滚）
-- 设计要点：
-- 1. versionNo 在同一 appId 内单调递增；UK(appId, versionNo) 是「并发下不会撞号」的最后一道防线
-- 2. contentHash 用来跳过「重新生成但产物一模一样」的无效版本，避免版本列表被噪声灌满
-- 3. isCurrent 标记当前生效版本，回滚就是「切标记 + 把快照目录写回生成目录」
-- 4. 产物本身不放数据库（可能几百 MB），只存相对路径，便于把版本根目录外置/迁移
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
    UNIQUE KEY uk_app_versionNo (appId, versionNo), -- 防止并发打快照撞号
    INDEX idx_appId_isCurrent (appId, isCurrent)    -- 「取当前版本」是最高频查询
) comment '应用版本' collate = utf8mb4_unicode_ci;
