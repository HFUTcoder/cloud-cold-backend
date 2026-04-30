# cloud-cold-agent

`cloud-cold-agent` 是 `cloud-cold-frontend` 对应的后端项目。当前实现是一个基于 `Spring Boot 3 + Spring AI + MyBatis-Flex + Redis Session + Elasticsearch + MinIO` 的 AI Agent 服务，主线能力集中在会话式问答、Skill 工作流、HITL 人工确认，以及知识库 PDF 入库与检索。

## 1. 当前实现概览

- 用户注册、登录、注销，登录态基于 `HttpSession + Redis Session`
- 会话创建、会话列表、历史消息查询、会话绑定 Skill、会话绑定知识库
- Agent 流式问答，支持 `fast`、`thinking`、`expert`
- Skill 工作流预处理：识别会话已绑定 Skill、候选 Skill、增强问题、生成执行计划
- HITL 审批与恢复执行：对指定工具调用做人工确认、修改参数、继续执行
- 知识库创建、编辑、删除、分页查询
- PDF 文档上传、MinIO 存储、Elasticsearch 关键词索引、向量索引
- 本地 Skill 目录加载、Skill 内容读取、Skill 脚本执行

## 2. 和代码一致的关键事实

- 默认端口是 `8081`，统一接口前缀是 `/api`
- 默认 Profile 是 `local`
- 登录态来自 `HttpSession + Redis`，前端联调必须保留 Cookie
- `fast -> SimpleReactAgent`
- `thinking -> PlanExecuteAgent`
- `expert -> PlanExecuteAgent`
- 前端当前只暴露 `fast` 和 `thinking`，但后端仍支持 `expert`
- Agent 主工具池当前只注册了 2 个工具：
  - `search`
  - `execute_skill_script`
- 仓库里虽然还有 `tools/rag/*` 等工具实现，但 `AgentServiceImpl` 当前注入的是 `@Qualifier("commonTools")`，并不会自动进入主执行链
- 会话绑定知识库后的检索不是通过 `commonTools` 完成的，而是先由 `KnowledgePreprocessService` 在进入 Agent 之前做一次服务层 `hybridSearch`
- SSE 事件名固定是 `agent`
- 当前后端实际发送的主要事件类型有：
  - `thinking_step`
  - `assistant_delta`
  - `final_answer`
  - `hitl_interrupt`
  - `knowledge_retrieval`
  - `error`
- `knowledge_retrieval` 只会在知识库预检索命中图片时发送
- Web 层把 `Long` / `long` 统一序列化成 `string`，避免前端精度丢失
- 当前文档入库真正支持的格式只有 `PDF`
- PDF 入库链路当前是“文本 chunk + 图片描述 chunk”双通道入索引，不是把图片描述直接混进主文本后统一切块
- 聊天历史当前会持久化最终回答，并能回放命中的知识库图片；思考过程不会作为历史消息持久化

## 3. 技术栈

- Java 21
- Spring Boot 3.5.13
- Spring MVC + `SseEmitter`
- Spring AI 1.1.2
- Spring AI Alibaba Agent Framework 1.1.2.0
- MyBatis-Flex 1.11.1
- MySQL 8.x
- Redis + Spring Session
- Elasticsearch 8.x
- Spring AI Elasticsearch Vector Store
- MinIO 8.5.1
- Knife4j / OpenAPI 3
- GraalVM Polyglot Python

## 4. 项目结构

```text
cloud-cold-agent
├── AGENT.md
├── README.md
├── pom.xml
├── src/main/java/com/shenchen/cloudcoldagent
│   ├── agent/                 # SimpleReactAgent / PlanExecuteAgent
│   ├── aop/                   # @AuthCheck 拦截
│   ├── common/                # BaseResponse / SSE event factory
│   ├── config/                # Spring、ES、MinIO、Tool、Session 配置
│   ├── controller/            # REST + SSE 接口
│   ├── document/              # 文档读取、清洗、切分、向量化、存储
│   ├── hitl/                  # HITL advisor 与状态对象
│   ├── mapper/                # MyBatis-Flex Mapper
│   ├── memory/                # 聊天记忆 MySQL 持久化
│   ├── model/                 # DTO / VO / Entity / Record
│   ├── prompts/               # Prompt 模板
│   ├── service/               # 业务服务
│   ├── tools/                 # Agent Tools
│   ├── utils/                 # 工具类
│   └── workflow/skill/        # Skill 工作流图、节点、状态对象
├── src/main/resources
│   ├── application.yml
│   ├── mapper/
│   └── skills/                # 本地 Skill 示例
└── src/test
```

## 5. 核心链路

### 5.1 登录与会话

- `POST /api/user/login` 登录后，把用户对象写入 Session
- Session 由 Redis 托管，默认超时 `2592000s`
- `POST /api/chatConversation/create` 会创建 `conv_` 前缀的会话 id
- `POST /api/agent/call` 如果没有传 `conversationId`，Controller 会先自动创建会话
- 会话标题在“默认标题 + 首条消息 + 当前无历史记录”时，会被截成首条问题前 5 个字符

### 5.2 Agent 调用链路

1. 前端调用 `POST /api/agent/call`
2. `AgentController` 创建 `SseEmitter`
3. 无 `conversationId` 时自动创建会话
4. `AgentServiceImpl` 统一做：
   - `touchConversation(...)`
   - `generateTitleOnFirstMessage(...)`
   - Skill 工作流预处理
   - 知识库预检索预处理
5. 如果 Skill 执行计划命中“缺少必填参数”的阻塞条件，会直接短路返回 `final_answer`
6. `fast` 路由到 `SimpleReactAgent`
7. `thinking` / `expert` 路由到 `PlanExecuteAgent`
8. 流式事件通过 SSE 持续推给前端

### 5.3 Skill 工作流链路

当前工作流图定义在 `workflow/skill/config/SkillWorkflowConfig.java`，顺序是：

1. `loadBoundSkills`
2. `loadConversationHistory`
3. `recognizeBoundSkills`
4. `discoverCandidateSkills`
5. `loadSkillContents`
6. `buildSkillExecutionPlans`
7. `buildEnhancedQuestion`

当前输出的关键结果包括：

- `selectedSkills`
- `executionPlans`
- `enhancedQuestion`

### 5.4 知识库预检索链路

如果当前会话已绑定知识库，进入 Agent 前会先做一次服务层预处理：

1. `KnowledgePreprocessService.preprocess(...)`
2. 内部调用 `KnowledgeService.hybridSearch(userId, knowledgeId, question)`
3. 把命中的文本 chunk 组装成增强问题
4. 如果命中图片描述 chunk，会解析图片 id，再生成可访问的图片 URL
5. 通过 `knowledge_retrieval` 事件把命中的图片推给前端
6. 同时把图片暂存到 `ChatMemoryPendingImageBindingService`
7. 后续消息落库时，再把这些命中图片绑定到聊天历史

这条链路很重要，因为它说明：

- 知识库问答当前是“服务层预检索 + Agent 消费增强问题”
- 不是依赖 `commonTools` 中的 RAG 工具边问边搜

### 5.5 HITL 审批与恢复执行

1. `PlanExecuteAgent` 运行过程中命中需要人工确认的工具
2. `HitlCheckpointService` 创建 `hitl_checkpoint`
3. SSE 推送 `hitl_interrupt`
4. 前端调用 `POST /api/hitl/checkpoint/resolve`
5. 前端再调用 `POST /api/agent/resume`
6. `HitlResumeServiceImpl` 根据审批结果补写工具响应，继续执行后续链路

当前默认配置：

- `cloudcold.hitl.enabled=true`
- `cloudcold.hitl.intercept-tool-names=execute_skill_script`

当前只有 `PlanExecuteAgent` 支持 `resume`

### 5.6 知识库文档入库链路

1. 前端调用 `POST /api/document/upload`
2. 原始 PDF 上传到 MinIO
3. 写入 `knowledge_document` 元数据，状态先置为 `PENDING`
4. 状态切到 `INDEXING`
5. 临时落盘到本地文件
6. `DocumentReaderFactory` 选择 `PdfMultimodalProcessor`
7. 解析 PDF 文本，并抽取页面图片
8. 对图片调用多模态模型生成描述
9. 文本内容生成 `TEXT` 类型 chunk
10. 图片描述生成 `IMAGE_DESCRIPTION` 类型 chunk
11. 关键词索引写入 ES `rag_docs`
12. 向量索引写入 ES vector store `rag_docs_vector`
13. 成功后状态改为 `INDEXED`；失败则清理索引、图片记录并改为 `FAILED`

补充说明：

- `PdfMultimodalProcessor` 当前只把正文文本写入主文档内容
- 图片描述不会直接插入正文后再统一切块
- 图片描述是在 `KnowledgeService.buildImageDescriptionChunks(...)` 中单独建索引 chunk

## 6. 基础设施与配置

### 6.1 本地开发依赖

- JDK 21
- Maven 3.9+，或直接使用 `./mvnw`
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- 一个 OpenAI 兼容的聊天模型服务
- 一个 OpenAI 兼容的 Embedding 服务
- 如果要启用 PDF 图片识别，还需要一个可用的多模态模型

### 6.2 `application.yml` 当前默认值

- `server.port=8081`
- `server.servlet.context-path=/api`
- `spring.profiles.active=local`
- `spring.datasource.url=jdbc:mysql://localhost:3306/cloud_cold`
- `spring.datasource.username=root`
- `spring.datasource.password=1234`
- `spring.data.redis.host=localhost`
- `spring.elasticsearch.uris=http://localhost:9200`
- `minio.endpoint=http://localhost:9000`
- `minio.bucketName=cloud-cold`
- `spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/`
- `spring.ai.openai.chat.options.model=qwen-turbo`
- `spring.ai.openai.embedding.options.model=text-embedding-v4`
- `cloudcold.upload.max-file-size=100MB`
- `cloudcold.search.mock.enabled=true`
- `cloudcold.hitl.enabled=true`
- `cloudcold.pdf.multimodal.model=qwen3-vl-plus`

### 6.3 常用配置组

- `spring.datasource.*`
- `spring.data.redis.*`
- `spring.elasticsearch.*`
- `spring.ai.openai.*`
- `spring.ai.alibaba.toolcalling.tavilysearch.*`
- `spring.ai.vectorstore.elasticsearch.*`
- `minio.*`
- `cloudcold.upload.*`
- `cloudcold.search.mock.*`
- `cloudcold.hitl.*`
- `cloudcold.pdf.multimodal.*`

### 6.4 本地覆盖建议

建议在本地补一个未提交的 `src/main/resources/application-local.yml` 覆盖账号、密码和模型配置，例如：

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
          model: qwen-turbo
      embedding:
        options:
          model: text-embedding-v4
    alibaba:
      toolcalling:
        tavilysearch:
          enabled: true
          api-key: your_tavily_key
  data:
    redis:
      password: ""
  elasticsearch:
    username: ""
    password: ""
    insecure: false

minio:
  endpoint: http://localhost:9000
  access-key: your_minio_access_key
  secret-key: your_minio_secret_key
  bucket-name: cloud-cold

cloudcold:
  search:
    mock:
      enabled: false
  pdf:
    multimodal:
      api-key: your_multimodal_api_key
      base-url: https://your-openai-compatible-endpoint
      model: qwen3-vl-plus
```

补充说明：

- 当前搜索工具默认是 mock 结果
- 想走真实联网搜索，需要同时关闭 `cloudcold.search.mock.enabled`，并正确配置 Tavily 搜索能力
- MinIO bucket 不存在时会自动创建，并设置为公共读

## 7. 数据库初始化

初始化脚本：

- `src/main/java/com/shenchen/cloudcoldagent/sql/init.sql`

当前会创建的核心表有：

- `user`
- `chat_memory_history`
- `chat_memory_history_image_relation`
- `chat_conversation`
- `user_conversation_relation`
- `conversation_skill_relation`
- `conversation_knowledge_relation`
- `hitl_checkpoint`
- `knowledge`
- `knowledge_document`
- `knowledge_document_image`

初始化脚本会写入 3 个测试账号：

- `admin / 12345678`
- `user / 12345678`
- `test / 12345678`

密码加密方式是：

- `MD5(password + "salt")`

## 8. 启动方式

### 8.1 初始化数据库

先创建数据库并执行 `init.sql`。

### 8.2 启动基础依赖

确保这些服务可用：

- MySQL
- Redis
- Elasticsearch
- MinIO
- OpenAI 兼容模型服务

### 8.3 启动项目

```bash
./mvnw spring-boot:run
```

或：

```bash
./mvnw clean package
java -jar target/cloud-cold-agent-0.0.1-SNAPSHOT.jar
```

启动后默认地址：

- 服务地址：[http://localhost:8081/api](http://localhost:8081/api)
- Knife4j：[http://localhost:8081/api/doc.html](http://localhost:8081/api/doc.html)

## 9. 接口概览

接口统一前缀为 `/api`。

### 9.1 登录与用户

- `POST /user/register`
- `POST /user/login`
- `GET /user/get/login`
- `POST /user/logout`

管理员接口：

- `POST /user/add`
- `GET /user/get`
- `POST /user/delete`
- `POST /user/update`
- `POST /user/list/page/vo`

当前实现中这些接口没有显式 `@AuthCheck`：

- `GET /user/get/vo`

### 9.2 会话与聊天历史

- `POST /chatConversation/create`
- `GET /chatConversation/list/my`
- `GET /chatConversation/get`
- `POST /chatConversation/update/skills`
- `POST /chatConversation/update/knowledge`
- `POST /chatConversation/delete`
- `GET /chatMemory/history/list/conversation`
- `GET /chatMemory/history/list/user`
- `GET /chatMemory/history/list/user/conversations`
- `POST /chatMemory/history/delete`

### 9.3 Agent 与 HITL

- `POST /agent/call`
- `POST /agent/resume`
- `GET /hitl/checkpoint/get`
- `GET /hitl/checkpoint/latest`
- `POST /hitl/checkpoint/resolve`

### 9.4 Skill

当前 Skill 接口没有显式 `@AuthCheck`：

- `GET /skill/list`
- `GET /skill/meta/{skillName}`
- `GET /skill/{skillName}`
- `GET /skill/resource`
- `POST /skill/script/execute`

### 9.5 知识库与文档

- `POST /knowledge/create`
- `GET /knowledge/get`
- `POST /knowledge/update`
- `POST /knowledge/delete`
- `POST /knowledge/list/page/my`
- `POST /knowledge/write`
- `POST /knowledge/scalar-search`
- `POST /knowledge/metadata-search`
- `POST /knowledge/vector-search`
- `POST /knowledge/hybrid-search`
- `POST /document/create`
- `POST /document/upload`
- `GET /document/get`
- `GET /document/preview-url`
- `POST /document/update`
- `POST /document/delete`
- `POST /document/list/page/my`
- `GET /document/list/by/knowledge`

### 9.6 调试 / 辅助接口

这些接口更偏调试或底层对象存储辅助，不属于前端主链路：

- `POST /files/upload`
- `GET /files/download-url/{objectName}`
- `GET|POST /es/write`
- `GET|POST /es/search`

补充说明：

- `files/*` 和 `es/*` 当前返回的不是统一 `BaseResponse<T>` 包装

## 10. 与 cloud-cold-frontend 的联动

前端项目是 `cloud-cold-frontend`。

- 前端普通 JSON 请求固定带 `credentials: 'include'`
- 前端 Agent 流式接口不是 `EventSource`，而是手动解析 `text/event-stream`
- 本地开发时，前端请求地址优先级是：
  1. `VITE_API_BASE_URL`
  2. 如果浏览器运行在 `localhost` / `127.0.0.1` 且端口不是 `8081`，直接请求 `http://localhost:8081/api`
  3. 其他情况才走相对路径 `/api`
- 因此前端虽然保留了 Vite `/api -> 8081` 代理，但在最常见的 `localhost:5173` 开发场景下，请求往往会直接打到 `http://localhost:8081/api`

后端如果修改以下内容，前端通常也要同步：

- SSE 事件类型和字段
- `conversationId` / `interruptId` 语义
- Agent 模式值
- 会话绑定 Skill / 知识库字段
- 聊天历史里的 `retrievedImages`
- `Long -> string` 的序列化策略

## 11. 常见排查点

- 登录后仍提示未登录：先检查 Cookie 是否成功携带，以及 Redis Session 是否可用
- SSE 没有内容：先看 `/agent/call` 响应头是否为 `text/event-stream`
- 知识库问答没有命中图片：检查预检索是否命中了 `IMAGE_DESCRIPTION` chunk，以及 MinIO 预签名 URL 是否生成成功
- 文档上传成功但知识库不可用：重点检查 MinIO、Elasticsearch、Embedding 模型、PDF 多模态模型是否正常
- 搜索结果像假数据：当前默认 `cloudcold.search.mock.enabled=true`
- 联网搜索不生效：确认已关闭 mock，并正确配置 Tavily 搜索能力
- 前端比较 `id` 时行为怪异：优先确认是不是后端返回了字符串 ID
