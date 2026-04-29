# cloud-cold-agent

`cloud-cold-agent` 是 `cloud-cold-frontend` 对应的后端项目，一个基于 `Spring Boot + Spring AI + Elasticsearch + MinIO + Redis` 的 AI Agent 服务。项目围绕“会话式智能体 + Skill + 知识库 + HITL（Human-in-the-loop）审批”构建，支持流式对话、知识库文档入库与检索、工具调用、人工确认后继续执行等能力。

## 主要能力

- 用户注册、登录、注销，基于 `Http Session + Redis` 维护登录态
- 会话创建、会话列表、历史消息查询、会话绑定 Skill
- AI Agent 流式问答，支持 `fast` 与 `thinking` 两种模式
- Skill 工作流预处理，自动识别并增强问题
- HITL 工具审批，可对待执行工具调用进行确认、拒绝或修改后恢复执行
- 知识库管理，支持知识库创建、更新、删除、分页查询
- 文档上传入库，落 MinIO 后写入 Elasticsearch 关键词索引与向量索引
- 文档检索，支持标量检索、向量检索、元数据检索、混合检索
- 本地 Skill 目录加载与脚本执行

## 技术栈

- Java 21
- Spring Boot 3.5
- Spring AI 1.1
- Spring AI Alibaba Agent Framework
- MyBatis Flex
- MySQL
- Redis + Spring Session
- Elasticsearch
- MinIO
- Knife4j / OpenAPI 3
- GraalVM Polyglot Python

## 项目结构

```text
cloud-cold-agent
├── pom.xml
├── README.md
├── src/main/java/com/shenchen/cloudcoldagent
│   ├── agent                # Agent 实现，如 ReactAgent / PlanExecuteAgent
│   ├── annotation           # 权限注解
│   ├── aop                  # 鉴权切面
│   ├── common               # 通用返回、SSE 事件等
│   ├── config               # Spring / ES / MinIO / Skill / Redis 配置
│   ├── controller           # REST 接口
│   ├── document             # 文档解析、切分、向量化、存储
│   ├── hitl                 # HITL 拦截与状态管理
│   ├── mapper               # MyBatis Flex Mapper
│   ├── model                # DTO / VO / Entity / Record / Enum
│   ├── prompts              # Agent Prompt 模板
│   ├── service              # 业务服务
│   ├── tools                # Agent Tool 实现
│   ├── utils                # 工具类
│   └── workflow             # Skill 工作流
├── src/main/resources
│   ├── application.yml
│   ├── mapper
│   └── skills               # 本地 Skills 目录
└── src/test
```

## 核心业务链路

### 1. Agent 问答链路

- 前端调用 `/api/agent/call`
- 后端根据 `mode` 选择：
  - `fast` -> `SimpleReactAgent`
  - `thinking` -> `PlanExecuteAgent`
- 在真正执行前，先经过 `SkillWorkflowService` 对问题做 Skill 识别、增强和执行计划生成
- 结果通过 `SSE` 持续返回给前端

### 2. HITL 审批链路

- 当 `thinking` 模式下命中特定工具调用时，会触发 HITL 中断
- 后端创建 `hitl_checkpoint`
- 前端拉取待审批工具调用并展示
- 用户确认后调用 `/api/hitl/checkpoint/resolve`
- 前端再调用 `/api/agent/resume` 恢复执行

### 3. 知识库文档入库链路

- 前端上传文件到 `/api/document/upload`
- 后端先把原文件存到 MinIO
- 再写入 `knowledge_document` 元数据
- 解析文档并切分为 chunks
- 将 chunks 写入 Elasticsearch 关键词索引与向量索引
- 更新文档状态为 `INDEXED` / `FAILED`

## 运行环境要求

本项目本地开发至少需要以下组件：

- JDK 21
- Maven 3.9+（也可直接使用仓库内 `./mvnw`）
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- 可用的 OpenAI 兼容模型服务

## 默认配置

项目默认配置位于 `src/main/resources/application.yml`：

- 服务端口：`8081`
- 接口前缀：`/api`
- MySQL：`jdbc:mysql://localhost:3306/cloud_cold`
- Redis：`localhost:6379`
- Elasticsearch：`http://localhost:9200`
- MinIO：`http://localhost:9000`
- 上传限制来源：`cloudcold.upload.*`
- 向量索引名：`rag_docs_vector`
- 当前默认开启 `cloudcold.search.mock.enabled=true`

## 本地配置建议

仓库中没有提交完整的本地敏感配置，建议新增 `src/main/resources/application-local.yml`，补齐数据库密码、MinIO 账号、模型配置等内容。

可以参考下面的示例：

```yaml
spring:
  datasource:
    username: root
    password: your_mysql_password
  ai:
    openai:
      base-url: https://your-openai-compatible-endpoint
      api-key: your_api_key
      chat:
        options:
          model: gpt-4o-mini
      embedding:
        options:
          model: text-embedding-3-small
  data:
    redis:
      password: ""
  elasticsearch:
    username: ""
    password: ""
    insecure: false

minio:
  access-key: your_minio_access_key
  secret-key: your_minio_secret_key

cloudcold:
  upload:
    max-file-size: 100MB
    max-request-size: 100MB
```

说明：

- `spring.ai.openai.api-key` 是必填项，项目中的 Agent 与部分文档处理逻辑都会依赖它
- `minio.access-key` / `minio.secret-key` 是必填项，否则 MinIO 客户端无法初始化
- 上传大小限制统一由 `cloudcold.upload.max-file-size` 和 `cloudcold.upload.max-request-size` 控制
- 当前代码默认使用 `spring.profiles.active=local`，因此建议把本地覆盖配置写在 `application-local.yml`
- 如果后续关闭 `cloudcold.search.mock.enabled`，还需要补齐真实联网搜索工具所需配置

## 数据库初始化

初始化 SQL 位于：

- `src/main/java/com/shenchen/cloudcoldagent/sql/init.sql`

执行后会创建以下核心表：

- `user`
- `chat_conversation`
- `chat_memory_history`
- `user_conversation_relation`
- `hitl_checkpoint`
- `knowledge`
- `knowledge_document`

同时会插入测试账号：

- 管理员：`admin / 12345678`
- 普通用户：`user / 12345678`
- 测试用户：`test / 12345678`

## 启动步骤

### 1. 初始化数据库

先创建数据库并执行 `init.sql`。

### 2. 启动基础依赖

确保以下服务可用：

- MySQL
- Redis
- Elasticsearch
- MinIO
- OpenAI 兼容模型服务

### 3. 补齐本地配置

按上文创建并完善 `application-local.yml`。

### 4. 启动项目

在项目根目录执行：

```bash
./mvnw spring-boot:run
```

或先打包再运行：

```bash
./mvnw clean package
java -jar target/cloud-cold-agent-0.0.1-SNAPSHOT.jar
```

启动成功后默认访问地址为：

- 服务地址：[http://localhost:8081/api](http://localhost:8081/api)
- Knife4j 文档：[http://localhost:8081/api/doc.html](http://localhost:8081/api/doc.html)

## 与前端项目的联动

前端项目为 `cloud-cold-frontend`。

- 前端开发态默认通过 Vite 代理把 `/api` 转发到 `http://localhost:8081`
- 后端采用 `Session + Cookie` 维持登录态，因此前后端联调时要保留 `credentials: include`
- `Agent` 接口为 `SSE` 流式响应，前端会持续消费返回事件

## 主要接口概览

接口统一前缀为 `/api`。

### 用户

- `POST /user/register` 用户注册
- `POST /user/login` 用户登录
- `GET /user/get/login` 获取当前登录用户
- `POST /user/logout` 用户注销

### 会话与历史消息

- `POST /chatConversation/create` 创建会话
- `GET /chatConversation/list/my` 获取当前用户会话列表
- `GET /chatConversation/get` 获取会话详情
- `POST /chatConversation/update/skills` 更新会话绑定 Skill
- `POST /chatConversation/delete` 删除会话
- `GET /chatMemory/history/list/conversation` 获取会话消息历史

### Agent

- `POST /agent/call` 发起智能体流式调用
- `POST /agent/resume` 恢复被 HITL 中断的执行

### HITL

- `GET /hitl/checkpoint/get` 按 `interruptId` 获取检查点
- `GET /hitl/checkpoint/latest` 获取某会话最新待处理检查点
- `POST /hitl/checkpoint/resolve` 提交审批结果

### Skill

- `GET /skill/list` 获取 Skill 列表
- `GET /skill/meta/{skillName}` 获取 Skill 元信息
- `GET /skill/{skillName}` 读取 Skill 内容
- `GET /skill/resource` 读取 Skill 资源
- `POST /skill/script/execute` 执行 Skill 脚本

### 知识库

- `POST /knowledge/create` 创建知识库
- `GET /knowledge/get` 查询知识库
- `POST /knowledge/update` 更新知识库
- `POST /knowledge/delete` 删除知识库
- `POST /knowledge/list/page/my` 分页查询我的知识库
- `POST /knowledge/scalar-search` 关键词检索
- `POST /knowledge/metadata-search` 元数据检索
- `POST /knowledge/vector-search` 向量检索
- `POST /knowledge/hybrid-search` 混合检索

### 文档

- `POST /document/upload` 上传文档并入库
- `GET /document/get` 查询文档
- `GET /document/preview-url` 获取文档预览地址
- `POST /document/update` 更新文档
- `POST /document/delete` 删除文档
- `GET /document/list/by/knowledge` 查询知识库下文档列表

## Skills

本项目支持从本地目录自动加载 Skills：

- Skill 根目录：`src/main/resources/skills`
- 当前已内置示例 Skill：
  - `两数之和`
  - `计算个人所得税`

加载逻辑在 `config/SkillConfig.java` 中，通过 `FileSystemSkillRegistry` 自动扫描。

## 鉴权说明

- 登录态保存在 `HttpSession`
- Session 存储在 Redis
- 鉴权通过 `@AuthCheck` + AOP 切面实现
- 大部分业务接口要求先登录
- 管理员接口要求 `admin` 角色

## 开发注意事项

- Web 层已经对 `Long` 做了 `String` 序列化，避免前端精度丢失
- 当前跨域配置允许所有来源，但生产环境建议收紧
- `thinking` 模式比 `fast` 模式更重，依赖更多 Agent 编排与 HITL 能力
- 文档删除时会联动删除 ES 索引、向量索引和 MinIO 对象
- 当前前端知识库上传入口主要按 PDF 使用场景设计，若要扩更多格式，建议同时检查前端限制与后端 Reader 策略

## 常见排查

### 1. 项目启动后登录报未登录或会话失效

优先检查：

- Redis 是否正常启动
- 前端请求是否带了 Cookie
- 浏览器是否拦截了跨域凭证

### 2. 文档上传失败

优先检查：

- MinIO 账号密码是否正确
- Bucket 是否可用
- Elasticsearch 是否正常启动
- 模型 embedding 配置是否可用

### 3. Agent 能调用但无内容返回

优先检查：

- `spring.ai.openai.api-key` 是否正确
- 模型服务地址是否可达
- 前端是否正确消费 `SSE`

### 4. 搜索结果一直是固定文案

这是因为默认开启了：

```yaml
cloudcold:
  search:
    mock:
      enabled: true
```

关闭后再补真实搜索配置即可切换到联网搜索。
