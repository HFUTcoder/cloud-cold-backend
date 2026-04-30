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
    conversationId varchar(64)                          not null comment '会话 id',
    content        text                                 not null comment '消息内容',
    messageType    varchar(32)                          not null comment '消息类型：USER/ASSISTANT/SYSTEM/TOOL',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    index idx_conversationId_createTime (conversationId, createTime),
    index idx_conversationId_isDelete (conversationId, isDelete)
) comment '聊天历史记忆' collate = utf8mb4_unicode_ci;

create table if not exists chat_memory_history_image_relation
(
    id             bigint auto_increment comment '主键 id' primary key,
    historyId      bigint                               not null comment '聊天历史记录 id',
    conversationId varchar(64)                          not null comment '会话 id',
    imageId        bigint                               not null comment '知识库图片 id',
    sortOrder      int        default 0                 not null comment '图片顺序',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    index idx_historyId_isDelete (historyId, isDelete),
    index idx_conversationId_isDelete (conversationId, isDelete),
    index idx_imageId_isDelete (imageId, isDelete)
) comment '聊天历史消息图片关联表' collate = utf8mb4_unicode_ci;


-- 会话表（用于管理用户的会话列表）
create table if not exists chat_conversation
(
    id             bigint auto_increment comment '主键 id' primary key,
    conversationId varchar(64)                          not null comment '会话 id',
    title          varchar(255)                         null comment '会话标题（可选）',
    lastActiveTime datetime   default CURRENT_TIMESTAMP not null comment '最后活跃时间',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_conversationId (conversationId)
) comment '聊天会话' collate = utf8mb4_unicode_ci;

create table if not exists user_conversation_relation
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '用户 id',
    conversationId varchar(64)                          not null comment '会话 id',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_userId_conversationId (userId, conversationId),
    index idx_userId_isDelete (userId, isDelete),
    index idx_conversationId_isDelete (conversationId, isDelete)
) comment '用户会话关联表' collate = utf8mb4_unicode_ci;

create table if not exists conversation_skill_relation
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '用户 id',
    conversationId varchar(64)                          not null comment '会话 id',
    skillName      varchar(255)                         not null comment '绑定的 skill 名称',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_userId_conversationId_skillName (userId, conversationId, skillName),
    index idx_userId_isDelete (userId, isDelete),
    index idx_conversationId_isDelete (conversationId, isDelete),
    index idx_skillName_isDelete (skillName, isDelete)
) comment '会话技能关联表' collate = utf8mb4_unicode_ci;

create table if not exists conversation_knowledge_relation
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '用户 id',
    conversationId varchar(64)                          not null comment '会话 id',
    knowledgeId    bigint                               not null comment '绑定的知识库 id',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_userId_conversationId_knowledge (userId, conversationId),
    index idx_userId_isDelete (userId, isDelete),
    index idx_conversationId_isDelete (conversationId, isDelete),
    index idx_knowledgeId_isDelete (knowledgeId, isDelete)
) comment '会话知识库关联表' collate = utf8mb4_unicode_ci;

create table if not exists hitl_checkpoint
(
    id                    bigint auto_increment comment '主键 id' primary key,
    conversationId        varchar(64)                          not null comment '会话 id',
    interruptId           varchar(64)                          not null comment '中断 id',
    agentType             varchar(64)                          null comment 'agent 类型',
    pendingToolCallsJson  longtext                             not null comment '待确认工具调用 JSON',
    checkpointMessagesJson longtext                            not null comment '消息快照 JSON',
    contextJson           longtext                             null comment '上下文 JSON',
    feedbacksJson         longtext                             null comment '审批反馈 JSON',
    status                varchar(32)                          not null comment '状态：PENDING/RESOLVED/CANCELLED',
    resolvedTime          datetime                             null comment '处理完成时间',
    createTime            datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime            datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete              tinyint    default 0                 not null comment '是否删除',
    unique key uk_interruptId (interruptId),
    index idx_conversationId_status (conversationId, status)
) comment 'HITL 中断检查点' collate = utf8mb4_unicode_ci;

create table if not exists knowledge
(
    id                     bigint auto_increment comment '主键 id' primary key,
    userId                 bigint                               not null comment '所属用户 id',
    knowledgeName          varchar(255)                         not null comment '知识库名称',
    description            varchar(1024)                        null comment '知识库描述',
    documentCount          int        default 0                 not null comment '知识库下文档数量',
    lastDocumentUploadTime datetime                             null comment '最后一次上传文档时间',
    createTime             datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime             datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete               tinyint    default 0                 not null comment '是否删除',
    index idx_userId_isDelete (userId, isDelete),
    index idx_knowledgeName (knowledgeName)
) comment '知识库' collate = utf8mb4_unicode_ci;

create table if not exists knowledge_document
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '所属用户 id',
    knowledgeId    bigint                               not null comment '所属知识库 id',
    documentName   varchar(255)                         not null comment '文档名称',
    documentUrl    varchar(1024)                        null comment '文档访问链接（通常来自 MinIO）',
    objectName     varchar(512)                         null comment 'MinIO 对象名称',
    documentSource varchar(1024)                        null comment 'ES 文档 source 标识',
    fileType       varchar(64)                          null comment '文件类型',
    contentType    varchar(128)                         null comment 'Content-Type',
    fileSize       bigint                               null comment '文件大小（字节）',
    indexStatus    varchar(64) default 'PENDING'        not null comment '索引状态',
    chunkCount     int        default 0                 not null comment '切片数量',
    indexErrorMessage varchar(1024)                     null comment '索引失败原因',
    indexStartTime datetime                             null comment '索引开始时间',
    indexEndTime   datetime                             null comment '索引结束时间',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    index idx_userId_knowledgeId (userId, knowledgeId),
    index idx_documentName (documentName),
    index idx_documentSource (documentSource(255)),
    index idx_objectName (objectName),
    index idx_indexStatus (indexStatus)
) comment '知识库文档元数据' collate = utf8mb4_unicode_ci;

create table if not exists knowledge_document_image
(
    id             bigint auto_increment comment '主键 id' primary key,
    userId         bigint                               not null comment '所属用户 id',
    knowledgeId    bigint                               not null comment '所属知识库 id',
    documentId     bigint                               not null comment '所属文档 id',
    imageIndex     int                                  not null comment '图片序号，按文档内顺序从 0 开始',
    objectName     varchar(512)                         not null comment 'MinIO 对象名称',
    imageUrl       varchar(1024)                        null comment 'MinIO 访问地址',
    contentType    varchar(128)                         null comment '图片 Content-Type',
    fileSize       bigint                               null comment '图片大小（字节）',
    pageNumber     int                                  null comment '图片所在 PDF 页码',
    description    text                                 null comment '多模态识别后的图片描述',
    createTime     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint    default 0                 not null comment '是否删除',
    unique key uk_documentId_imageIndex (documentId, imageIndex),
    index idx_documentId_isDelete (documentId, isDelete),
    index idx_knowledgeId_isDelete (knowledgeId, isDelete),
    index idx_objectName (objectName)
) comment '知识库文档解析图片关系表' collate = utf8mb4_unicode_ci;
