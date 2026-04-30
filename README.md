# cloud-cold-agent

`cloud-cold-agent` 是 `cloud-cold-frontend` 对应的后端项目，一个基于 `Spring Boot + Spring AI + Elasticsearch + MinIO + Redis` 的 AI Agent 服务。项目围绕“会话式智能体 + Skill 工作流 + 知识库 + HITL（Human-in-the-loop）审批”构建，支持流式问答、工具调用、人工确认后恢复执行，以及文档入库与检索。

## 主要能力

- 用户注册、登录、注销，基于 `Http Session + Redis` 维护登录态
- 会话创建、会话列表、历史消息查询、会话绑定 Skill
- AI Agent 流式问答，支持 `fast`、`thinking`、`expert`
- 调用 Agent 前先经过 Skill 工作流预处理，完成 Skill 识别、问题增强和执行计划生成
- HITL 工具审批，可对待执行工具调用进行确认、拒绝或修改后恢复执行
- 知识库管理，支持创建、更新、删除、分页查询
- 文档上传入库，原文件存储到 MinIO，文本与向量索引写入 Elasticsearch
- 文档检索，支持标量检索、向量检索、元数据检索、混合检索
- 本地 Skill 目录加载、Skill 资源读取与脚本执行

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
│   ├── agent                # Agent 实现，如 SimpleReactAgent / PlanExecuteAgent
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

### 1. Agent 调用链路

1. 前端调用 `/api/agent/call`
2. `AgentController` 建立 `SseEmitter`
3. `AgentServiceImpl` 规范化会话、触达会话活跃时间、首次消息生成标题
4. `SkillWorkflowService` 先执行问题预处理
5. 根据模式路由到具体 Agent：
   - `fast` -> `SimpleReactAgent`
   - `thinking` -> `PlanExecuteAgent`
   - `expert` -> `PlanExecuteAgent`
6. 结果通过 `SSE` 事件持续返回前端

当前 SSE 事件类型主要包括：

- `thinking_step`
- `assistant_delta`
- `final_answer`
- `hitl_interrupt`
- `error`

### 2. Skill 预处理链路

真正进入 Agent 推理前，会先执行一轮 Skill 工作流：

- 识别候选 Skill
- 识别会话绑定 Skill
- 生成增强后的问题
- 生成可执行的 Skill 执行计划
- 如果命中缺少必填参数的阻塞计划，直接短路返回提示，不进入 Agent 主执行链

### 3. HITL 审批链路

- `PlanExecuteAgent` 执行过程中如果命中需要拦截的工具调用，会创建 `hitl_checkpoint`
- 当前默认开启 HITL，并默认拦截 `execute_skill_script`
- 后端向前端发出 `hitl_interrupt`
- 前端提交审批结果到 `/api/hitl/checkpoint/resolve`
- 前端再调用 `/api/agent/resume`
- 后端根据 `interruptId` 找到检查点并恢复执行

### 4. 知识库文档入库链路

1. 前端上传文件到 `/api/document/upload`
2. 后端写入文档元数据
3. 原文件存入 MinIO
4. 解析文档并切分为 chunks
5. chunks 写入 Elasticsearch 关键词索引与向量索引
6. 更新文档索引状态

## 运行环境要求

本项目本地开发至少需要以下组件：

- JDK 21
- Maven 3.9+，或直接使用仓库内 `./mvnw`
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- 可用的 OpenAI 兼容模型服务

## 默认配置

项目默认配置位于 `src/main/resources/application.yml`。

- 服务端口：`8081`
- 接口前缀：`/api`
- 默认 Profile：`local`
- MySQL：`jdbc:mysql://localhost:3306/cloud_cold`
- Redis：`localhost:6379`
- Elasticsearch：`http://localhost:9200`
- MinIO：`http://localhost:9000`
- 上传大小：`cloudcold.upload.max-file-size=100MB`
- 向量索引名：`rag_docs_vector`
- 当前默认开启 `cloudcold.search.mock.enabled=true`
- 当前默认开启 `cloudcold.hitl.enabled=true`

## 本地配置建议

仓库中没有提交完整的本地敏感配置，建议新增 `src/main/resources/application-local.yml`，补齐数据库密码、MinIO 账号、模型配置等内容。

示例：

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

补充说明：

- `spring.ai.openai.api-key` 是必填项，Agent 与部分文档处理逻辑依赖它
- `minio.access-key` 与 `minio.secret-key` 是必填项，否则 MinIO 客户端无法初始化
- 当前默认 `spring.profiles.active=local`
- 如果关闭 `cloudcold.search.mock.enabled`，还需要补齐真实联网搜索工具所需配置

## 数据库初始化

初始化 SQL 位于：

- `src/main/java/com/shenchen/cloudcoldagent/sql/init.sql`

执行后会创建的核心表包括：

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
- 后端采用 `Session + Cookie` 维持登录态，联调时必须保留 `credentials: include`
- Agent 接口为 `SSE` 流式响应，前端会手动消费事件流

## 主要接口概览

接口统一前缀为 `/api`。

### 用户

- `POST /user/register`
- `POST /user/login`
- `GET /user/get/login`
- `POST /user/logout`

### 会话与消息

- `POST /chatConversation/create`
- `GET /chatConversation/list/my`
- `GET /chatConversation/get`
- `POST /chatConversation/update/skills`
- `POST /chatConversation/delete`
- `GET /chatMemory/history/list/conversation`

### Agent 与 HITL

- `POST /agent/call`
- `POST /agent/resume`
- `GET /hitl/checkpoint/get`
- `GET /hitl/checkpoint/latest`
- `POST /hitl/checkpoint/resolve`

### Skill

- `GET /skill/list`
- `GET /skill/meta/{skillName}`
- `GET /skill/{skillName}`
- `GET /skill/resource`
- `POST /skill/script/execute`

### 知识库与文档

- `POST /knowledge/create`
- `GET /knowledge/get`
- `POST /knowledge/update`
- `POST /knowledge/delete`
- `POST /knowledge/list/page/my`
- `POST /knowledge/scalar-search`
- `POST /knowledge/metadata-search`
- `POST /knowledge/vector-search`
- `POST /knowledge/hybrid-search`
- `POST /document/upload`
- `GET /document/get`
- `GET /document/preview-url`
- `GET /document/list/by/knowledge`
- `POST /document/delete`

## 开发提示

- `AgentController` 只负责鉴权、会话兜底和 SSE 出口，主要编排逻辑在 `AgentServiceImpl`
- `SimpleReactAgent` 负责快速模式
- `PlanExecuteAgent` 负责多轮规划执行、HITL 中断与恢复
- `src/main/resources/skills` 下放的是本地 Skill 示例，可直接作为调试入口
