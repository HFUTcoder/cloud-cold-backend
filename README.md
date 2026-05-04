# cloud-cold-agent

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端项目。当前仓库实现的是一个基于 `Spring Boot 3 + Spring AI + MyBatis-Flex + Redis Session + Elasticsearch + MinIO` 的 AI Agent 服务，主线能力集中在 5 个方向：

- 会话式 Agent 对话
- Skill 工作流与脚本执行
- HITL 人工审批与恢复执行
- 知识库 PDF 入库、检索与图片回显
- 用户长期记忆 / 宠物记忆

## 1. 当前实现的关键事实

- 默认服务地址是 `http://localhost:8081/api`，默认 Profile 是 `local`
- 登录态来自 `HttpSession + Redis Session`，前端联调必须保留 Cookie
- 后端支持 3 种 Agent 模式：
  - `fast` -> `SimpleReactAgent`
  - `thinking` -> `PlanExecuteAgent`
  - `expert` -> `PlanExecuteAgent`
- 前端当前只暴露 `fast` 和 `thinking`，但后端仍保留 `expert`
- Agent 主工具池当前只注册了 2 个工具：
  - `search`
  - `execute_skill_script`
- 会话绑定知识库后的问答不是通过 RAG tool 边问边搜，而是先由 `KnowledgePreprocessService` 做一次服务层 `hybridSearch(...)`，再把增强问题交给 Agent
- 文档入库当前真正支持的格式只有 `PDF`
- PDF 入库是“正文 `TEXT` chunk + 图片描述 `IMAGE_DESCRIPTION` chunk”双通道索引，不是把图片描述直接混进正文统一切块
- `POST /document/upload` 当前是同步入库链路，请求会一直执行到索引成功或失败后才返回
- Web 层会把 `Long` / `long` 统一序列化成 `string`
- 聊天历史当前只持久化用户消息和助手最终回答；思考过程不会落库，但知识库命中的图片会和历史消息回绑
- 搜索工具默认走 mock 结果：`cloudcold.search.mock.enabled=true`
- 长期记忆默认启用，当前 `application.yml` 的运行值是：
  - `cloudcold.long-term-memory.enabled=true`
  - `cloudcold.long-term-memory.trigger-rounds=5`
  - `cloudcold.long-term-memory.default-pet-name=小云`

## 2. 技术栈

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
- GraalVM Polyglot Python
- Knife4j / OpenAPI 3

## 3. 项目结构

```text
cloud-cold-agent
├── AGENT.md
├── README.md
├── pom.xml
├── src/main/java/com/shenchen/cloudcoldagent
│   ├── agent/                 # SimpleReactAgent / PlanExecuteAgent
│   ├── aop/                   # @AuthCheck / 分布式锁切面
│   ├── common/                # BaseResponse / SSE 事件工厂
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

## 4. 核心业务链路

### 4.1 登录与会话

1. `POST /api/user/login` 登录后把用户信息写入 Session
2. Session 由 Redis 托管，默认超时 `2592000s`
3. `POST /api/chatConversation/create` 创建 `conv_` 前缀的会话 id
4. `POST /api/agent/call` 如果没有传 `conversationId`，`AgentController` 会先自动创建会话
5. `AgentServiceImpl` 会在每次对话前执行：
   - `touchConversation(...)`
   - `generateTitleOnFirstMessage(...)`
6. 当前会话标题在“默认标题 + 首条消息 + 当前无历史记录”时，会被更新为首条问题前 5 个字符

### 4.2 Agent 调用链路

1. 前端调用 `POST /api/agent/call`
2. `AgentController` 创建 `SseEmitter`
3. `AgentServiceImpl.call(...)` 做统一编排：
   - Skill 工作流预处理
   - 知识库预检索预处理
   - 长期记忆预处理
4. 如果 Skill 执行计划命中“缺少必填参数”的阻塞条件，会直接短路返回 `final_answer`
5. `fast` 路由到 `SimpleReactAgent`
6. `thinking` / `expert` 路由到 `PlanExecuteAgent`
7. SSE 事件名固定为 `agent`

当前主要事件类型：

- `thinking_step`
- `assistant_delta`
- `final_answer`
- `hitl_interrupt`
- `knowledge_retrieval`
- `error`

### 4.3 Skill 工作流链路

当前工作流图定义在 `workflow/skill/config/SkillWorkflowConfig.java`，顺序是：

1. `loadBoundSkills`
2. `loadConversationHistory`
3. `recognizeBoundSkills`
4. `discoverCandidateSkills`
5. `loadSkillContents`
6. `buildSkillExecutionPlans`
7. `buildEnhancedQuestion`

关键输出包括：

- `selectedSkills`
- `executionPlans`
- `enhancedQuestion`

### 4.4 知识库预检索链路

如果会话已绑定知识库，进入 Agent 前会先做一次服务层预处理：

1. `KnowledgePreprocessService.preprocess(...)`
2. 内部调用 `KnowledgeService.hybridSearch(userId, knowledgeId, question)`
3. 命中的文本 chunk 会被拼进增强问题
4. 命中的图片描述 chunk 会被解析成可访问图片 URL
5. 命中图片时先通过 `knowledge_retrieval` 事件推给前端
6. 这些图片会先暂存到 `ChatMemoryPendingImageBindingService`
7. 后续消息落库时，再把图片和聊天历史做回绑

这意味着当前知识库问答的真实模式是：

- 服务层预检索
- Agent 消费增强问题

而不是：

- Agent 运行时动态调用 RAG tool

### 4.5 HITL 审批与恢复执行

1. `PlanExecuteAgent` 运行过程中命中需要人工确认的工具
2. `HitlCheckpointService` 创建 `hitl_checkpoint`
3. SSE 推送 `hitl_interrupt`
4. 前端调用 `POST /api/hitl/checkpoint/resolve`
5. 前端再调用 `POST /api/agent/resume`
6. `HitlResumeServiceImpl` 根据审批结果补写工具响应并恢复执行

当前默认配置：

- `cloudcold.hitl.enabled=true`
- `cloudcold.hitl.intercept-tool-names=[execute_skill_script]`

当前只有 `PlanExecuteAgent` 支持 `resume`

### 4.6 文档入库链路

1. 前端调用 `POST /api/document/upload`
2. 原始 PDF 上传到 MinIO
3. 写入 `knowledge_document` 元数据，状态先置为 `PENDING`
4. 状态切到 `INDEXING`
5. 临时落盘到本地文件
6. `DocumentReaderFactory` 选择 `PdfMultimodalProcessor`
7. 解析 PDF 文本，并抽取页面图片
8. 对图片调用多模态模型生成描述
9. 正文生成 `TEXT` chunk
10. 图片描述生成 `IMAGE_DESCRIPTION` chunk
11. 关键词索引写入 ES `rag_docs`
12. 向量索引写入 ES `rag_docs_vector`
13. 成功后状态改为 `INDEXED`；失败则清理索引、图片记录并改为 `FAILED`

补充说明：

- `PdfMultimodalProcessor` 当前只支持 PDF
- 图片描述是在 `KnowledgeService.buildImageDescriptionChunks(...)` 中单独建索引 chunk
- `POST /document/upload` 返回时，索引流程已经结束，不是异步排队模式

### 4.7 长期记忆 / 宠物记忆链路

当前长期记忆不是实验代码，而是已经接到主链路里的能力：

1. 用户提问前，`AgentServiceImpl` 会调用 `UserLongTermMemoryPreprocessService.preprocess(...)`
2. 命中相关记忆后，会生成运行时 `system prompt` 注入 Agent
3. 助手最终回答落库后，`MysqlChatMemoryRepository` 会通知 `UserLongTermMemoryService`
4. 同一会话累计达到 `trigger-rounds=5` 的新增完整轮次后，会异步触发记忆整理
5. 另外有一个整点任务 `UserLongTermMemoryScheduler`，每小时兜底扫描待处理会话
6. 前端通过 `/api/userMemory/*` 暴露宠物状态、记忆列表、重建、改名、删除接口

## 5. 环境依赖与配置

### 5.1 本地依赖

- JDK 21
- Maven 3.9+，或直接使用 `./mvnw`
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- 一个 OpenAI 兼容的聊天模型服务
- 一个 OpenAI 兼容的 Embedding 服务
- 一个可用的 PDF 多模态模型

### 5.2 当前 `application.yml` 的关键运行值

| 配置项 | 当前值 |
| --- | --- |
| `server.port` | `8081` |
| `server.servlet.context-path` | `/api` |
| `spring.profiles.active` | `local` |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/cloud_cold` |
| `spring.datasource.username` | `root` |
| `spring.datasource.password` | `1234` |
| `spring.data.redis.host` | `localhost` |
| `spring.elasticsearch.uris` | `http://localhost:9200` |
| `minio.endpoint` | `http://localhost:9000` |
| `minio.bucketName` | `cloud-cold` |
| `spring.ai.openai.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/` |
| `spring.ai.openai.chat.options.model` | `qwen-turbo` |
| `spring.ai.openai.embedding.options.model` | `text-embedding-v4` |
| `cloudcold.search.mock.enabled` | `true` |
| `cloudcold.hitl.enabled` | `true` |
| `cloudcold.hitl.intercept-tool-names` | `execute_skill_script` |
| `cloudcold.long-term-memory.enabled` | `true` |
| `cloudcold.long-term-memory.trigger-rounds` | `5` |
| `cloudcold.long-term-memory.default-pet-name` | `小云` |
| `cloudcold.pdf.multimodal.model` | `qwen3-vl-plus` |

还需要你自己补充的密钥类配置：

- `spring.ai.openai.api-key`
- `cloudcold.pdf.multimodal.api-key`
- 如果要关闭 mock 搜索并启用真实联网搜索，还需要：
  - `spring.ai.alibaba.toolcalling.tavilysearch.enabled=true`
  - `spring.ai.alibaba.toolcalling.tavilysearch.api-key`
  - 同时把 `cloudcold.search.mock.enabled=false`

### 5.3 本地覆盖建议

建议新建未提交的 `src/main/resources/application-local.yml` 覆盖账号、密码和密钥，例如：

```yaml
spring:
  datasource:
    username: root
    password: your_mysql_password
  ai:
    openai:
      api-key: your_api_key
      base-url: https://your-openai-compatible-endpoint
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

### 5.4 与当前实现强绑定的配置事实

- 代码里的 `@ConfigurationProperties` 默认值不一定等于当前运行值，请以 `application.yml` 为准
- 例如：
  - `SearchProperties.mock.enabled` 的代码默认值是 `false`，但仓库当前运行值是 `true`
  - `LongTermMemoryProperties.triggerRounds` 的代码默认值是 `6`，但仓库当前运行值是 `5`
  - `LongTermMemoryProperties.defaultPetName` 的代码默认值是 `小冷`，但仓库当前运行值是 `小云`

## 6. 数据库初始化

初始化脚本在：

- `src/main/java/com/shenchen/cloudcoldagent/sql/init.sql`

当前会创建的核心表包括：

- `user`
- `chat_memory_history`
- `chat_memory_history_image_relation`
- `chat_conversation`
- `user_conversation_relation`
- `user_long_term_memory`
- `user_long_term_memory_source_relation`
- `user_long_term_memory_conversation_state`
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

## 7. 启动方式

### 7.1 初始化数据库

先创建数据库并执行 `init.sql`。

### 7.2 启动基础依赖

确保这些服务已经可用：

- MySQL
- Redis
- Elasticsearch
- MinIO
- 聊天模型
- Embedding 模型
- PDF 多模态模型

### 7.3 启动项目

最稳妥的方式是在仓库根目录运行：

```bash
./mvnw spring-boot:run
```

也可以先打包再启动：

```bash
./mvnw clean package
java -jar target/cloud-cold-agent-0.0.1-SNAPSHOT.jar
```

但要注意一件当前实现非常关键的事实：

- `SkillConfig` 是从文件系统路径 `src/main/resources/skills` 直接加载本地 Skill
- 因此如果你用 `java -jar` 启动，工作目录仍然要能访问到这个路径
- 脱离源码目录单独分发裸 JAR 时，当前 SkillRegistry 初始化会失败，除非你同步调整 Skill 加载方式

启动后默认地址：

- 服务地址：[http://localhost:8081/api](http://localhost:8081/api)
- Knife4j：[http://localhost:8081/api/doc.html](http://localhost:8081/api/doc.html)

## 8. 接口概览

接口统一前缀为 `/api`。

### 8.1 用户

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

### 8.2 会话与聊天历史

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

### 8.3 Agent 与 HITL

- `POST /agent/call`
- `POST /agent/resume`
- `GET /hitl/checkpoint/get`
- `GET /hitl/checkpoint/latest`
- `POST /hitl/checkpoint/resolve`

### 8.4 Skill

当前这些接口没有显式 `@AuthCheck`：

- `GET /skill/list`
- `GET /skill/meta/{skillName}`
- `GET /skill/{skillName}`
- `GET /skill/resource`
- `POST /skill/script/execute`

### 8.5 知识库与文档

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

### 8.6 长期记忆 / 宠物记忆

- `GET /userMemory/pet/state`
- `GET /userMemory/list`
- `POST /userMemory/rebuild`
- `POST /userMemory/rename`
- `POST /userMemory/delete`

### 8.7 调试 / 辅助接口

这些接口更偏调试或底层对象存储辅助，不属于前端主链路：

- `POST /files/upload`
- `GET /files/download-url/{objectName}`
- `GET|POST /es/write`
- `GET|POST /es/search`

补充说明：

- `files/*` 和 `es/*` 当前返回的不是统一 `BaseResponse<T>`

## 9. 与 cloud-cold-frontend 的联动

前端项目是 `cloud-cold-frontend`。

- 前端普通 JSON 请求固定带 `credentials: 'include'`
- 前端 Agent 流式接口不是 `EventSource`，而是手动解析 `text/event-stream`
- 前端本地开发时，请求地址优先级是：
  1. `VITE_API_BASE_URL`
  2. 如果浏览器运行在 `localhost` / `127.0.0.1` 且端口不是 `8081`，直接请求 `http://localhost:8081/api`
  3. 其他情况才走相对路径 `/api`
- 因此前端虽然保留了 Vite `/api -> 8081` 代理，但在最常见的 `localhost:5173` 场景下，请求往往会直接打到 `http://localhost:8081/api`
- 前端当前只暴露两个模式：`fast`、`thinking`
- 前端当前只支持单 Skill 绑定，但后端底层关系表支持多 Skill
- 前端绑定知识库时会自动补建会话；绑定 Skill 时不会自动建会话

后端如果修改以下内容，前端通常也要同步：

- SSE 事件类型和字段
- `conversationId` / `interruptId` 语义
- Agent 模式值
- 会话绑定 Skill / 知识库字段
- 聊天历史里的 `retrievedImages`
- `Long -> string` 的序列化策略
- HITL payload 结构

## 10. 常见排查点

- 登录后仍提示未登录：先检查 Cookie 是否成功携带，以及 Redis Session 是否可用
- SSE 没有内容：先看 `/agent/call` 响应头是否为 `text/event-stream`
- 搜索结果像假数据：当前默认 `cloudcold.search.mock.enabled=true`
- 联网搜索不生效：确认已关闭 mock，并正确配置 Tavily 搜索能力
- 知识库问答没有命中图片：检查预检索是否命中了 `IMAGE_DESCRIPTION` chunk，以及 MinIO 预签名 URL 是否生成成功
- 文档上传耗时很长：当前上传接口是同步入库，不是异步任务队列
- 文档上传成功但知识库不可用：重点检查 MinIO、Elasticsearch、Embedding 模型、PDF 多模态模型是否正常
- 长期记忆迟迟没有刷新：先确认是否达到 `trigger-rounds=5`，再看整点调度或手动 `POST /userMemory/rebuild`
- 前端比较 `id` 时行为怪异：优先确认是不是后端返回了字符串 ID
