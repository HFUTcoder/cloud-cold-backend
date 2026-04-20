-- 创建库
create database if not exists cloud_cold;
use cloud_cold;

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

-- 初始化测试数据（密码是 12345678，MD5 加密 + 盐值 salt）
INSERT INTO user (id, userAccount, userPassword, userName, userAvatar, userProfile, userRole) VALUES
                                                                                                  (1, 'admin', '10670d38ec32fa8102be6a37f8cb52bf', '管理员', 'https://lf-flow-web-cdn.doubao.com/obj/flow-doubao/doubao/web/doubao_avatar.png', '系统管理员', 'admin'),
                                                                                                  (2, 'user', '10670d38ec32fa8102be6a37f8cb52bf', '普通用户', 'https://lf-flow-web-cdn.doubao.com/obj/flow-doubao/doubao/web/doubao_avatar.png', '我是一个普通用户', 'user'),                                                                                         (3, 'test', '10670d38ec32fa8102be6a37f8cb52bf', '测试账号', 'https://lf-flow-web-cdn.doubao.com/obj/flow-doubao/doubao/web/doubao_avatar.png', '这是一个测试账号', 'user');

-- 智能体历史记忆表（MySQL 持久化）
create table if not exists chat_memory_history
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               null comment '用户 id',
    conversationId varchar(64)                          not null comment '会话 id',
    content        text                                 not null comment '消息内容',
    messageType    varchar(32)                          not null comment '消息类型：USER/ASSISTANT/SYSTEM/TOOL',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    index idx_userId_createTime (userId, createTime),
    index idx_conversationId_createTime (conversationId, createTime),
    index idx_conversationId_isDelete (conversationId, isDelete)
) comment '聊天历史记忆' collate = utf8mb4_unicode_ci;

-- 会话表（用于管理用户的会话列表）
create table if not exists chat_conversation
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '用户 id',
    conversationId varchar(64)                          not null comment '会话 id',
    title          varchar(255)                         null comment '会话标题（可选）',
    lastActiveTime datetime   default CURRENT_TIMESTAMP not null comment '最后活跃时间',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_conversationId (conversationId),
    index idx_userId_lastActiveTime (userId, lastActiveTime),
    index idx_userId_isDelete (userId, isDelete)
) comment '聊天会话' collate = utf8mb4_unicode_ci;
