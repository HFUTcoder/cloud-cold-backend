# AGENTS.md

## 1. 项目概述

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端服务，提供会话式 Agent、Skill 工作流、HITL 人工审批、知识库 PDF 入库与检索、长期记忆 / 宠物记忆能力。

技术栈：Java 21、Spring Boot 3.5.13、Spring AI、Spring AI Alibaba Agent Framework、MyBatis-Flex、MySQL、Redis Session、Elasticsearch、MinIO、GraalVM Polyglot Python。

仓库核心代码在 `src/main/java/com/shenchen/cloudcoldagent`，配置与本地 Skill 在 `src/main/resources`，详细架构和开发说明在 `docs/`。

## 2. 快速命令

| 目的 | 命令 |
|------|------|
| 编译检查 | `./mvnw -q -DskipTests compile` |
| 运行测试 | `./mvnw test` |
| 启动后端 | `./mvnw spring-boot:run` |
| 打包 | `./mvnw clean package` |

服务默认运行在 `http://localhost:8081/api`，API 文档 `http://localhost:8081/api/doc.html`。

## 3. 配置

全部配置集中在 `src/main/resources/application.yml`，所有可配置项带注释说明。

- 密钥/密码以 `TODO:` 注释标记（共 4 处：DashScope API Key、MySQL 密码、MinIO 凭证、可选 Tavily Key）
- 本地覆盖文件：`src/main/resources/application-local.yml`（已 gitignore，不提交），可覆盖 `application.yml` 中的任意配置
- 默认 Profile：`local`

关键配置项（`application.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.openai.chat.options.model` | `qwen-turbo` | 聊天模型 |
| `spring.ai.openai.embedding.options.model` | `text-embedding-v4` | Embedding 模型（1536 维） |
| `spring.ai.openai.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/` | DashScope 兼容 API |
| `spring.session.store-type` | `redis` | Session 存储 |
| `spring.session.timeout` | `2592000`（30天） | Session 超时 |
| `server.port` | `8081` | 服务端口 |
| `server.servlet.context-path` | `/api` | 接口前缀 |
| `cloudcold.agent.react.max-rounds` | `5` | ReactAgent 最大轮次 |
| `cloudcold.agent.react.tool-concurrency` | `3` | ReactAgent 工具并行度 |
| `cloudcold.agent.plan.max-rounds` | `5` | PlanExecuteAgent 最大轮次 |
| `cloudcold.agent.plan.max-tool-retries` | `5` | 工具调用最大重试 |
| `cloudcold.agent.plan.context-char-limit` | `5000` | 上下文压缩字符阈值 |
| `cloudcold.agent.plan.tool-concurrency` | `3` | 工具并行度 |
| `cloudcold.agent.memory.max-messages` | `20` | 窗口记忆消息上限 |
| `cloudcold.hitl.enabled` | `true` | HITL 开关 |
| `cloudcold.hitl.intercept-tool-names` | `[execute_skill_script]` | 拦截工具列表 |
| `cloudcold.long-term-memory.enabled` | `true` | 长期记忆开关 |
| `cloudcold.long-term-memory.trigger-rounds` | `5` | 触发整理轮次阈值（Java 代码默认 6，YAML 覆盖为 5） |
| `cloudcold.long-term-memory.retrieve-top-k` | `5` | 向量召回 topK |
| `cloudcold.long-term-memory.similarity-threshold` | `0.5` | 相似度阈值 |
| `cloudcold.long-term-memory.max-prompt-memories` | `4` | 注入 prompt 的最大记忆数 |
| `cloudcold.long-term-memory.max-memory-chars` | `400` | 单条记忆最大字符数 |
| `cloudcold.long-term-memory.default-pet-name` | `小云` | 默认宠物名称 |
| `cloudcold.pdf.multimodal.model` | `qwen3-vl-plus` | PDF 多模态模型 |
| `cloudcold.search.mock.enabled` | `true` | 搜索 mock 开关 |
| `minio.bucketName` | `cloud-cold` | MinIO bucket |
| `skills.user-skills-dir` | `src/main/resources/user-skills` | 用户 Skill 目录 |

## 4. 后端架构

```text
src/main/java/com/shenchen/cloudcoldagent
├── agent/                 # BaseAgent、SimpleReactAgent、PlanExecuteAgent
├── annotation/            # @AuthCheck、@DistributeLock
├── aop/                   # AuthInterceptor、DistributeLockAspect
├── common/                # BaseResponse、AgentStreamEventFactory、ResultUtils、分页请求
├── config/                # Web、Tool、ES、MinIO、Session、RateLimiter、CORS、线程池、Skill
│   └── properties/        # Agent、ES、HITL、LongTermMemory、Minio、PdfMultimodal、Search、Upload 配置属性
├── constant/              # DistributeLockConstant、KnowledgeChunkConstant、UserConstant
├── context/               # AgentRuntimeContext
├── controller/            # REST + SSE 接口（11 个 Controller）
├── database/              # init.sql + ES mapping JSON 文件
│   ├── agent/             # AgentController
│   ├── chat/              # ChatConversationController、ChatMemoryHistoryController
│   ├── hitl/              # HitlCheckpointController
│   ├── knowledge/         # KnowledgeController、DocumentController
│   ├── user/              # UserController
│   ├── usermemory/        # UserLongTermMemoryController
│   ├── skill/             # SkillController
│   └── storage/           # EsController、FileController
├── document/              # 文档读取、清洗、切分、索引准备
│   ├── extract/cleaner/   # DocumentCleaner
│   ├── extract/reader/    # DocumentReaderStrategy → PdfMultimodalProcessor（当前唯一实现）
│   ├── load/embedding/    # EmbeddingService
│   ├── load/store/        # StoreService
│   └── transform/splitter/# OverlapParagraphTextSplitter、ParentTextSplitter
├── enums/                 # AgentModeEnum、DocumentIndexStatusEnum、HitlCheckpointStatusEnum、UserRoleEnum
├── exception/             # BusinessException、DistributeLockException、ErrorCode、GlobalExceptionHandler、HitlInterruptedException
├── hitl/                  # HITLState（ConcurrentHashMap 追踪已消费 toolCallId）
├── job/                   # UserLongTermMemoryScheduler（每小时整点扫描）
├── limiter/               # RateLimiter 接口、SlidingWindowRateLimiter
├── mapper/                # MyBatis-Flex Mapper 接口（14 个）
│   ├── chat/              # ChatConversationMapper、ChatMemoryHistoryMapper 等（6 个）
│   ├── hitl/              # HitlCheckpointMapper
│   ├── knowledge/         # KnowledgeMapper、KnowledgeDocumentImageMapper、DocumentMapper
│   ├── user/              # UserMapper
│   └── usermemory/        # UserLongTermMemoryMapper 等（3 个）
├── memory/store/          # MysqlChatMemoryRepository（聊天记忆 MySQL 持久化）
├── model/
│   ├── dto/               # 请求 DTO（agent/、chat/、document/、hitl/、knowledge/、skill/、user/、usermemory/）
│   ├── entity/            # 数据库实体 + record（usermemory/、agent/knowledge/、agent/planexecute/、hitl/、knowledge/、support/）
│   └── vo/                # 视图对象（usermemory/ 含 UserPetStateVO、UserLongTermMemoryVO）
├── prompts/               # BaseAgentPrompts、PlanExecutePrompts、ReactAgentPrompts、KnowledgePrompts、SkillWorkflowPrompts、UserLongTermMemoryPrompts
├── registry/              # SkillRegistry 接口、CachingSkillRegistry、FileSystemSkillRegistry
├── service/               # 业务接口（23 个）
│   ├── agent/             # AgentService
│   ├── chat/              # ChatConversationService、ChatMemoryHistoryService 等（7 个）
│   ├── hitl/              # HitlCheckpointService、HitlExecutionService、HitlResumeService
│   ├── knowledge/         # KnowledgeService、KnowledgePreprocessService 等（5 个）
│   ├── user/              # UserService
│   ├── skill/             # SkillService
│   ├── storage/           # ElasticSearchService、MinioService
│   └── usermemory/        # 长期记忆服务接口 + 实现（3 对）
├── tools/                 # Agent Tools
│   ├── common/            # SearchTool
│   ├── rag/               # AbstractKnowledgeSearchTool、KnowledgeHybridSearchTool、KnowledgeScalarSearchTool、KnowledgeVectorSearchTool
│   └── skill/             # ExecuteSkillScriptTool、ListSkillResourcesTool、ReadSkillResourceTool、ReadSkillTool
├── utils/                 # DeleteExceptionUtils、HitlSerializationUtils、JsonArgumentUtils、JsonUtil、PlanExecuteResumeUtils、PythonScriptRuntimeUtils、StateValueUtils、ThrowUtils
├── workflow/skill/        # Skill 工作流
│   ├── config/            # SkillWorkflowConfig（6 节点顺序图）
│   ├── node/              # 6 个 Node 实现
│   ├── service/           # SkillWorkflowService + StructuredOutputAgentExecutor
│   │   └── impl/          # SkillWorkflowServiceImpl
│   └── state/             # SkillRuntimeContext、SkillExecutionPlan 等状态对象
```

### 4.1 Agent 编排

路由逻辑（`AgentServiceImpl.init()`）：

| AgentModeEnum | Agent 实现 | 特点 |
|---|---|---|
| `FAST` ("fast") | `SimpleReactAgent` | 同步循环执行 tool call，Sinks.Many SSE 流式输出 |
| `THINKING` ("thinking") | `PlanExecuteAgent` | Plan → Execute → Critique → Summarize 全流程 |
| `EXPERT` ("expert") | `PlanExecuteAgent` | 同 THINKING |
| 默认 | `SimpleReactAgent` | — |

请求主链路（`AgentServiceImpl.call()`）：

1. 解析/规范化 `conversationId`（必要时自动创建会话）
2. 更新会话活跃时间、首问生成标题
3. **Skill 工作流预处理** → `skillWorkflowService.preprocess()`
4. **知识库预检索** → `knowledgePreprocessService.preprocess()`
5. **长期记忆召回** → `userLongTermMemoryPreprocessService.preprocess()`
6. 组装 runtime system prompt（长期记忆 prompt + Skill 上下文 prompt + 知识库绑定 prompt）
7. 注册待绑定知识库图片
8. 发送 `knowledge_retrieval` SSE 事件
9. 路由到 Agent 执行

SSE 事件类型：`thinking_step`、`assistant_delta`、`final_answer`、`hitl_interrupt`、`knowledge_retrieval`、`error`

### 4.2 SimpleReactAgent

- 使用 `ReActAgentPrompts.STRICT_REACT_SYSTEM_PROMPT` 作为基础 system prompt
- `internalToolExecutionEnabled(false)` — 手动控制 tool call 循环
- `stream()` 方法：通过 `Sinks.Many` 实现 SSE streaming，处理跨 chunk 的 tool call 合并
- 工具并发由 `Semaphore(toolConcurrency)` 控制（默认 3）
- 到达 `maxRounds` 时强制输出最终答案

### 4.3 PlanExecuteAgent

Plan-Execute-Critique-Summarize 周期：

1. **Plan**：LLM 生成 `List<PlanTask>`（`id`、`toolName`、`arguments`、`order`、`summary`）
2. **Execute**：按 `order` 分组（同 order 并行执行），HITL 拦截检查
3. **Critique**：LLM 评估进度，返回 `SUMMARIZE` / `CONTINUE` / `ASK_USER`
4. **Context Compression**：字数超过 `contextCharLimit`（默认 5000）时 LLM 压缩状态
5. **Summarize**：通过 `ChatClient.stream()` 流式输出最终答案

HITL 恢复：`resume()` 方法恢复 `ResumeContext`，应用编辑参数，继续剩余任务。审批结果：`APPROVED` / `REJECTED` / `EDIT`。全部 `REJECTED` 时输出拒绝信息。

### 4.4 Skill 工作流

工作流图定义在 `SkillWorkflowConfig.java`，6 个节点顺序执行：

1. `loadBoundSkills` — 获取会话已绑定的 Skill
2. `loadConversationHistory` — 加载对话历史
3. `recognizeBoundSkills` — LLM 判断已绑定 Skill 的相关性
4. `discoverCandidateSkills` — LLM 发现未绑定的候选 Skill
5. `loadSkillContents` — 读取相关 Skill 的 SKILL.md 内容
6. `buildSkillRuntimeContext` — 构建 `SkillRuntimeContext` 对象

`SkillWorkflowResult` 包含 `selectedSkills` 和 `selectedSkillContexts`。

Skill 来源：
- 项目内建：`src/main/resources/skills`
- 用户目录：`src/main/resources/user-skills`

`SkillConfig` 使用 `FileSystemSkillRegistry` 作为委托，通过 `CachingSkillRegistry` 缓存。

### 4.5 知识库与文档

**两级分块模型**：

PDF 文本中图像描述按原位注入后，先按段落切分父块（`PARENT`），再按 200 字符 / 50 字符重叠切分子块（`TEXT`）。

- **父块**：`chunkType=PARENT`，完整段落文本，含 `metadata.imageIds`（关联 MySQL 图像 ID）
- **子块**：`chunkType=TEXT`，~200 字符，含 `metadata.parentChunkId` 指向父块
- 父块写入关键词索引，子块写入关键词索引 + 向量索引（仅有 `TEXT` 子块向量化）
- 检索：标量检索查 `PARENT` 父块，向量检索查 `TEXT` 子块，向量结果在 RRF 融合前 resolve 回父块

**图像描述处理**：

PDF 解析时将图像描述以 `<cloudcoldagent-image id="N">描述文字</cloudcoldagent-image>` 标签注入文本原位，在分块阶段提取 imageIndex 映射为 MySQL 图像 ID 存入父块 metadata，然后剥离 XML 标签只保留描述文字。图像 ID 会传播到相邻段落，确保图像与其上下文段落关联。

**关键词检索**：使用 `content.ngram` 子字段（`edge_ngram` token filter，min_gram=1，max_gram=20）+ `match` 查询，中文/英文/数字均适用。

**预检索链路**（Agent 入口前执行）：

1. `KnowledgePreprocessServiceImpl.preprocess()` 检查会话是否绑定了 `selectedKnowledgeId`
2. `KnowledgeService.hybridSearch()` 混合检索：标量检索查父块 + 向量检索查子块 → resolve 到父块 → RRF 融合（最多 8 个片段、4000 字符）
3. 从父块 `metadata.imageIds` 提取关联图像 → 查 MySQL 获取 MinIO presigned URL → `knowledge_retrieval` SSE 事件
4. 父块文本 → `KnowledgePrompts` 组装增强问题
5. 图片暂存 `ChatMemoryPendingImageBindingService`，助手消息落库时回绑

**PDF 入库链路**（同步）：

1. 校验知识库归属 → 原始 PDF 上传 MinIO → 写入 `knowledge_document`（`PENDING` → `INDEXING`）
2. 临时落盘 → `PdfMultimodalProcessor` 读取
3. 提取正文文本 → 抽取 PDF 图片 → 多模态模型（`qwen3-vl-plus`）生成描述
4. 图像描述注入文本原位 → 段落切分父块 → 子块切分
5. 父块 + 子块写入关键词索引 `rag_docs`，TEXT 子块写入向量索引 `rag_docs_vector`（1536 维，cosine 相似度）
6. 成功 → `INDEXED`；失败 → 清理索引与图片记录 → `FAILED`

**当前仅支持 PDF**。`PdfMultimodalProcessor` 是 `DocumentReaderStrategy` 的唯一实现。

### 4.6 HITL 人工审批

- 拦截器触发：`PlanExecuteAgent.executeStructuredToolTask()` 中检查 `hitlInterceptToolNames.contains(toolName)`（默认 `execute_skill_script`）
- 中断流程：创建 `hitl_checkpoint` → `HITLState` 记录待消费 toolCallId → SSE 推送 `hitl_interrupt`
- 恢复流程：前端调用 `POST /hitl/checkpoint/resolve` → `POST /agent/resume`
- `HITLState` 用 `ConcurrentHashMap<String, Set<Long>>` 追踪已消费的 toolCallId，防止恢复后重复执行
- 默认配置：`cloudcold.hitl.enabled=true`，仅 `execute_skill_script` 被拦截

### 4.7 长期记忆 / 宠物记忆

**存储**：MySQL（`user_long_term_memory`、`user_long_term_memory_source_relation`、`user_long_term_memory_conversation_state`）+ ES 双索引（关键词索引 `user_long_term_memory_docs` + 向量索引 `user_long_term_memory_vector` 1536 维）+ Redis（宠物名称、最后学习时间）

**提取 Pipeline**：
1. 助手消息落库后 `MysqlChatMemoryRepository` 通知 `UserLongTermMemoryService`
2. 同一会话累计新增轮次达到 `triggerRounds` 时异步触发
3. LLM 结构化提取 → fallback 退化策略 → MySQL + ES 双写

**召回**：Agent 入口前 `UserLongTermMemoryPreprocessServiceImpl` 向量相似度召回（topK=5，阈值 0.5），最多 4 条拼入 runtime system prompt

**定时兜底**：`UserLongTermMemoryScheduler` 每小时整点扫描待处理会话

**API**：`/userMemory/pet/state`、`/userMemory/list`、`/userMemory/rebuild`、`/userMemory/rename`、`/userMemory/delete`

### 4.8 聊天记忆

- `MysqlChatMemoryRepository` 实现 `ChatMemoryRepository`，持久化到 `chat_memory_history` 表
- `saveAll()`：比较 common prefix 后追加新消息，助手消息落库时回绑知识库图片
- 窗口大小：`maxMessages=20`（`cloudcold.agent.memory.max-messages`）
- 不持久化实时思考过程，只持久化用户消息、助手最终回答和已回绑知识库图片（`ChatMemoryHistoryImageRelation`）

## 5. Agent 工具池

### commonTools（Agent 运行时可用 — 2 个）

`AgentServiceImpl` 注入 `@Qualifier("commonTools")`：

| Tool | `@Tool(name=...)` | 类 | 说明 |
|------|-------------------|-----|------|
| `search` | `search` | `SearchTool` | 搜索（Tavily 或 mock） |
| `execute_skill_script` | `execute_skill_script` | `ExecuteSkillScriptTool` | Python 脚本执行，也是 HITL 默认拦截目标 |

### allTools（全部已注册工具 — 8 个）

按类名排序，包含 `commonTools` 中的 2 个，外加 `tools/rag/` 和 `tools/skill/` 中的其余工具：

`search`、`knowledge_hybrid_search`、`knowledge_scalar_search`、`knowledge_vector_search`、`list_skill_resources`、`read_skill_resource`、`read_skill`、`execute_skill_script`

**关键**：`tools/rag/*` 中的知识库检索工具只存在于 `allTools`，不在 `commonTools`，因此 Agent 运行时无法调用。知识库检索走服务层预处理。

## 6. 接口列表

接口统一前缀 `/api`。标注 `@AuthCheck` 的接口需要登录态。

### 用户
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/user/register` | 无 |
| POST | `/user/login` | 无 |
| GET | `/user/get/login` | 无 |
| POST | `/user/logout` | 无 |
| GET | `/user/get/vo` | 无 |
| POST | `/user/add` | admin |
| GET | `/user/get` | admin |
| POST | `/user/delete` | admin |
| POST | `/user/update` | admin |
| POST | `/user/list/page/vo` | admin |

### 会话
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/chatConversation/create` | user |
| GET | `/chatConversation/list/my` | user |
| GET | `/chatConversation/get` | user |
| POST | `/chatConversation/update/skills` | user |
| POST | `/chatConversation/update/knowledge` | user |
| POST | `/chatConversation/delete` | user |

### 聊天历史
| 方法 | 路径 | 鉴权 |
|------|------|------|
| GET | `/chatMemory/history/list/conversation` | user |
| GET | `/chatMemory/history/list/user` | user |
| GET | `/chatMemory/history/list/user/conversations` | user |
| POST | `/chatMemory/history/delete` | user |

### Agent
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/agent/call` | user |
| POST | `/agent/resume` | user |

### HITL
| 方法 | 路径 | 鉴权 |
|------|------|------|
| GET | `/hitl/checkpoint/get` | user |
| GET | `/hitl/checkpoint/latest` | user |
| POST | `/hitl/checkpoint/resolve` | user |

### Skill（条件注册，`@ConditionalOnBean`）
| 方法 | 路径 | 鉴权 |
|------|------|------|
| GET | `/skill/list` | 无 |
| GET | `/skill/meta/{skillName}` | 无 |
| GET | `/skill/{skillName}` | 无 |
| GET | `/skill/resource` | 无 |
| POST | `/skill/script/execute` | 无 |

### 知识库
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/knowledge/create` | user |
| GET | `/knowledge/get` | user |
| POST | `/knowledge/update` | user |
| POST | `/knowledge/delete` | user |
| POST | `/knowledge/list/page/my` | user |
| POST | `/knowledge/write` | user |
| POST | `/knowledge/scalar-search` | user |
| POST | `/knowledge/metadata-search` | user |
| POST | `/knowledge/vector-search` | user |
| POST | `/knowledge/hybrid-search` | user |

### 文档
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/document/create` | user |
| POST | `/document/upload` | user |
| GET | `/document/get` | user |
| GET | `/document/preview-url` | user |
| POST | `/document/update` | user |
| POST | `/document/delete` | user |
| POST | `/document/list/page/my` | user |
| GET | `/document/list/by/knowledge` | user |

### 长期记忆
| 方法 | 路径 | 鉴权 |
|------|------|------|
| GET | `/userMemory/pet/state` | user |
| GET | `/userMemory/list` | user |
| POST | `/userMemory/rebuild` | user |
| POST | `/userMemory/rename` | user |
| POST | `/userMemory/delete` | user |

### 文件 / ES（调试）
| 方法 | 路径 | 鉴权 |
|------|------|------|
| POST | `/files/upload` | 无 |
| GET | `/files/download-url/{objectName}` | 无 |
| — | `/es/write`、`/es/search` | 无 |

## 7. 前后端契约

| 前端术语 | 后端术语 / 字段 |
|----------|----------------|
| Agent 模式 | `fast` / `thinking` / `expert`（`AgentModeEnum`） |
| 会话 | `conversationId` |
| Skill 绑定 | `selectedSkillList` |
| 知识库绑定 | `knowledgeId` / `selectedKnowledgeId` |
| HITL 中断 | `interruptId` / checkpoint |
| 知识库命中图片 | `knowledge_retrieval` / `retrievedImages` |
| 宠物记忆 | `userMemory` / `UserLongTermMemory` |
| 宠物状态 | `UserPetState`（enabled、petName、petMood、memoryCount 等） |

后端修改以下内容通常需要同步 `cloud-cold-frontend`：
- SSE 事件名、事件类型和 payload 字段
- Agent 模式值
- `conversationId`、`interruptId` 语义
- 会话绑定 Skill / 知识库字段
- 聊天历史里的 `retrievedImages`
- HITL checkpoint 和 resolve payload
- `Long → string` 的 JSON 序列化策略

## 8. 关键约定

- **Agent 工具池**：`commonTools`（`AgentServiceImpl` 中注入 `@Qualifier("commonTools")`），仅 `SearchTool` 和 `ExecuteSkillScriptTool`。`tools/rag/*` 中的 3 个知识库检索工具只存在于 `allTools`，不在 `commonTools`，Agent 运行时无法调用。
- **知识库检索**：主链路是进入 Agent 前的服务层预检索（`KnowledgePreprocessServiceImpl.preprocess()`），不是 Agent 运行时调用 rag tool。
- **文档上传**：`POST /document/upload` 是同步入库，返回时已经是 `INDEXED` 或 `FAILED`。
- **文档格式**：当前只支持 PDF。`PdfMultimodalProcessor` 是 `DocumentReaderStrategy` 的唯一实现。扩展格式需同步注册 `DocumentReaderStrategy`、前端上传限制和文档。
- **PDF 入库**：使用两级分块（父块 `PARENT` + 子块 `TEXT`）。图像描述通过 `<cloudcoldagent-image>` 标签注入文本原位，分块后剥离标签保留描述文字，图像 ID 存入父块 `metadata.imageIds`。不再使用 `IMAGE_DESCRIPTION` chunk 类型。
- **ES 检索**：关键词检索使用 `content.ngram` 子字段（`edge_ngram`）+ `match` 查询。标量检索只查 `PARENT` 父块，向量检索查 `TEXT` 子块，向量结果在 RRF 融合前 resolve 回父块。
- **ES 索引管理**：索引初始化集中在 `EsConfig.initEsIndices()`（`@EventListener(ApplicationReadyEvent.class)`），mapping JSON 文件位于 `src/main/java/com/shenchen/cloudcoldagent/database/`。不要在 ServiceImpl 中创建索引。
- **VectorStore**：两个命名 Bean — `ragVectorStore`（`@Primary`，索引 `rag_docs_vector`）和 `longTermMemoryVectorStore`（索引名由 `LongTermMemoryProperties.vectorIndexName` 配置）。均通过 `EsConfig` 配置，使用 `ElasticsearchVectorStore.builder().initializeSchema(true)`。
- **FilterExpression**：向量检索的元数据过滤使用 `Filter.Expression` 对象 API（`new Filter.Expression(ExpressionType.EQ, new Filter.Key(key), new Filter.Value(value))`），不拼字符串再走 `FilterExpressionTextParser`，避免大数值溢出。
- **`Long` / `long` 序列化**：`WebConfig` 中 `ToStringSerializer` 统一序列化成 JSON 字符串，修改属于前后端契约级改动。
- **聊天记忆**：不持久化实时思考过程，只持久化用户消息、助手最终回答和已回绑知识库图片（`ChatMemoryHistoryImageRelation`）。
- **长期记忆**：已接入主链路（`AgentServiceImpl` 中调用 `UserLongTermMemoryPreprocessService`），修改提取 Prompt、ES 索引 mapping、memory type 枚举时需同步前后端。
- **长期记忆清理**：ES 与 MySQL 必须保持一致性。删除 memory 时先查 MySQL 拿到 memoryId 列表，再分别删除 MySQL 和 ES（`deleteByIds`）。`rebuildConversationMemories` 中必须先 LLM 提取成功再删除旧数据。
- **数据访问**：MySQL 主链路使用 MyBatis-Flex，不要平行引入 JPA、MyBatis-Plus 或另一套数据访问主框架。
- **复杂逻辑**：放在 Service / Workflow / Agent 层，不要塞进 Controller。

## 9. 本地开发及验证

1. 改动代码或文档
2. 编译检查：`./mvnw -q -DskipTests compile`
3. 启动本地服务：`./mvnw spring-boot:run`
4. 登录验证：`http://localhost:8081/api/doc.html` 或调用 `/api/user/login`
5. 前端联调：启动 `cloud-cold-frontend`，确认 Cookie 携带、SSE 响应头和知识库/HITL/长期记忆主链路

curl 登录模板：

```bash
curl -i -c /tmp/cloud-cold-cookie.txt \
  -H 'Content-Type: application/json' \
  -d '{"userAccount":"user","userPassword":"12345678"}' \
  http://localhost:8081/api/user/login
```

curl 当前用户模板：

```bash
curl -b /tmp/cloud-cold-cookie.txt \
  http://localhost:8081/api/user/get/login
```

curl 宠物状态模板：

```bash
curl -b /tmp/cloud-cold-cookie.txt \
  http://localhost:8081/api/userMemory/pet/state
```

## 10. 质量检查

| 检查项 | 命令 | 说明 |
|--------|------|------|
| 编译 | `./mvnw -q -DskipTests compile` | 最小验证 |
| 测试 | `./mvnw test` | 运行后端测试 |
| 打包 | `./mvnw clean package` | 生成可运行 JAR |
| 接口文档 | `http://localhost:8081/api/doc.html` | 启动后人工检查 |

## 11. 参考优先级

1. 当前仓库真实代码和 `application.yml`
2. `cloud-cold-frontend` 的实际调用方式
3. `docs/design-docs/ref-backend-architecture.md`
4. Spring Boot / Spring AI / MyBatis-Flex 官方约定

不要用脚手架默认习惯覆盖当前仓库已经形成的主链路约定。

## 12. 文档导航

| 文档 | 内容 |
|------|------|
| `README.md` | 人类快速上手和项目概览 |
| `docs/architecture.md` | 分层架构、核心链路、接口概览、长期记忆架构 |
| `docs/development.md` | 本地环境、配置、启动、验证、排查 |
| `docs/design-docs/ref-backend-architecture.md` | 参考项目架构说明 |
| `docs/design-docs/backend-patterns.md` | 后端组件、Tool、Skill、文档、长期记忆模式 |
