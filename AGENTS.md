# AGENTS.md

## 1. 项目概述

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端服务，提供会话式 Agent、Skill 工作流、HITL 人工审批、知识库 PDF 入库与检索、长期记忆 / 宠物记忆能力。技术栈是 Java 21、Spring Boot 3.5.13、Spring AI、Spring AI Alibaba Agent Framework、MyBatis-Flex、MySQL、Redis Session、Elasticsearch、MinIO 和 GraalVM Polyglot Python。仓库核心代码在 `src/main/java/com/shenchen/cloudcoldagent`，配置与本地 Skill 在 `src/main/resources`，详细架构和开发说明在 `docs/`。

## 2. 快速命令

| 目的 | 命令 |
| --- | --- |
| 编译检查 | `./mvnw -q -DskipTests compile` |
| 启动后端 | `./mvnw spring-boot:run` |
| 运行测试 | `./mvnw test` |
| 打包 | `./mvnw clean package` |

配置入口：

- 全部配置集中在 `src/main/resources/application.yml`，包含所有可配置项和注释说明
- 需要替换的密钥/密码以 `TODO:` 注释标记（共 4 处：DashScope API Key、MySQL 密码、MinIO 凭证、可选 Tavily Key）
- 本地覆盖（可选）：`src/main/resources/application-local.yml`（已 gitignore，不提交），可覆盖 `application.yml` 中的任意配置
- 默认服务：`http://localhost:8081/api`
- Knife4j：`http://localhost:8081/api/doc.html`

环境、密钥和启动细节见 [docs/development.md](docs/development.md)。

## 3. 后端架构

```text
src/main/java/com/shenchen/cloudcoldagent
├── agent/                 # SimpleReactAgent / PlanExecuteAgent
├── aop/                   # 鉴权、分布式锁切面
├── common/                # BaseResponse、SSE 事件工厂
├── config/                # Web、Tool、ES、MinIO、Session、RateLimiter、LongTermMemory 线程池
├── controller/            # REST + SSE 接口
├── document/              # 文档读取、清洗、切分、索引准备
│   └── extract/reader/    # DocumentReaderStrategy（当前仅 PdfMultimodalProcessor）
├── enums/                 # AgentModeEnum、DocumentIndexStatusEnum、HitlCheckpointStatusEnum、UserRoleEnum
├── hitl/                  # HITL advisor 与状态对象
├── memory/                # 聊天记忆 MySQL 持久化（MysqlChatMemoryRepository）
├── model/
│   ├── entity/usermemory/ # 长期记忆实体、Doc、提取结果、会话状态
│   ├── dto/usermemory/    # 宠物改名请求
│   └── vo/usermemory/     # 宠物状态 VO、长期记忆 VO
├── prompts/               # 长期记忆提取 Prompt、知识库 Prompt
├── service/
│   ├── usermemory/        # 长期记忆服务接口
│   └── impl/usermemory/   # 长期记忆服务实现
├── tools/                 # Agent Tools
├── workflow/skill/        # Skill 工作流图、节点、状态对象
└── job/                   # UserLongTermMemoryScheduler（每小时整点）
```

核心子系统：

- **Agent 编排**：`AgentController` -> `AgentServiceImpl` -> `SimpleReactAgent` / `PlanExecuteAgent`。请求进入后依次执行：Skill 工作流预处理 → 知识库预检索 → 长期记忆召回 → 组装 runtime system prompt → 路由到 Agent
- **Skill**：`workflow/skill/*` 负责绑定、识别、执行计划和增强问题
- **知识库**：`KnowledgePreprocessServiceImpl` 做进入 Agent 前预检索（hybrid search），`KnowledgeDocumentIngestionServiceImpl` 和 `KnowledgeServiceImpl` 做 PDF 入库与检索。文档上传是同步的：`POST /document/upload` 返回时已经是 INDEXED 或 FAILED
- **HITL**：`PlanExecuteAgent`、`HitlCheckpointServiceImpl`、`HitlResumeServiceImpl` 处理人工审批和恢复。中断后通过 `POST /agent/resume` 恢复执行
- **长期记忆 / 宠物记忆**：
  - **存储**：MySQL（`user_long_term_memory`、`user_long_term_memory_source_relation`、`user_long_term_memory_conversation_state`）+ ES 双索引（关键词索引 `user_long_term_memory_docs`、向量索引 `user_long_term_memory_vector` 1536 维）+ Redis（宠物名称、最后学习时间）
  - **提取 Pipeline**：助手消息落库后累计轮次，达到 `triggerRounds` 阈值时异步触发 LLM 结构化提取 → fallback 退化策略 → MySQL + ES 双写
  - **召回**：进入 Agent 前通过 `UserLongTermMemoryPreprocessService` 向量相似度召回相关记忆，拼入 runtime system prompt
  - **定时兜底**：`UserLongTermMemoryScheduler` 每小时整点扫描待处理会话
  - **API**：`/userMemory/pet/state`、`/userMemory/list`、`/userMemory/rebuild`、`/userMemory/rename`、`/userMemory/delete`
  - **配置**：前缀 `cloudcold.long-term-memory`，详见 `LongTermMemoryProperties`

前后端术语映射：

| 前端术语 | 后端术语 / 字段 |
| --- | --- |
| Agent 模式 | `fast` / `thinking` / `expert`（`AgentModeEnum`） |
| 会话 | `conversationId` |
| Skill 绑定 | `selectedSkillList` |
| 知识库绑定 | `knowledgeId` / `selectedKnowledgeId` |
| HITL 中断 | `interruptId` / checkpoint |
| 知识库命中图片 | `knowledge_retrieval` / `retrievedImages` |
| 宠物记忆 | `userMemory` / `UserLongTermMemory` |
| 宠物状态 | `UserPetState`（enabled, petName, petMood, memoryCount 等） |

详细说明见 [docs/architecture.md](docs/architecture.md) 和 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。

## 4. 前端架构

前端项目是同级仓库 `cloud-cold-frontend`，技术栈为 Vue 3、TypeScript、Vite。后端需要知道的前端契约：

- 前端普通请求固定带 `credentials: 'include'`，登录态来自后端 `HttpSession + Redis Session`
- 前端 Agent 流式接口使用 `fetch + getReader` 手动解析 `text/event-stream`，不是 `EventSource`
- 前端当前只暴露 `fast` 和 `thinking`，后端仍保留 `expert`
- 前端当前只支持单 Skill 绑定，后端底层支持多 Skill
- 前端知识库上传只允许 PDF
- 后端 `Long -> string` 序列化（`WebConfig` 中 `ToStringSerializer`）会直接影响前端 ID 比较和类型声明
- 前端 `PetMemoryWidget.vue` 调用 `/userMemory/*` 全套接口，宠物面板通过 `localStorage` 持久化 FAB 位置

更多联动契约见 [docs/architecture.md](docs/architecture.md)。

## 5. 关键约定

- Agent 主工具池是 `commonTools`（`ToolRegistrationConfig` 中注册），不是 `allTools`；当前只注册 `SearchTool` 和 `ExecuteSkillScriptTool`。`tools/rag/*` 中的知识库检索工具只存在于 `allTools`，不走 Agent 运行时调用。详见 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。
- 知识库问答主链路是进入 Agent 前的服务层预检索（`KnowledgePreprocessServiceImpl.preprocess()`），不是 `tools/rag/*` 运行时调用。详见 [docs/architecture.md](docs/architecture.md)。
- 当前文档入库只支持 PDF；扩展格式必须同步后端 `DocumentReaderStrategy`（`DocumentReaderFactory` 中注册）、前端上传限制和文档。详见 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。
- PDF 入库必须同时处理正文 `TEXT` chunk、图片描述 `IMAGE_DESCRIPTION` chunk、MinIO、`knowledge_document_image`、关键词索引和向量索引。详见 [docs/architecture.md](docs/architecture.md)。
- `POST /document/upload` 是同步入库，请求返回时已经进入 `INDEXED` 或 `FAILED`。详见 [docs/development.md](docs/development.md)。
- `Long` / `long` 统一通过 `WebConfig` 中的 `ToStringSerializer` 序列化成 JSON 字符串；修改它属于前后端契约级改动。详见 [docs/architecture.md](docs/architecture.md)。
- 聊天历史不持久化实时思考过程，只持久化用户消息、助手最终回答和已回绑知识库图片（`ChatMemoryHistoryImageRelation`）。详见 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。
- 长期记忆已接入主链路（`AgentServiceImpl` 中调用 `UserLongTermMemoryPreprocessService`），不是实验代码。修改提取 Prompt、ES 索引 mapping、memory type 枚举时必须同步前后端。详见 [docs/architecture.md](docs/architecture.md)。
- 长期记忆的 ES 与 MySQL 清理必须保持一致性：删除 memory 时先查 MySQL 拿到 memoryId 列表，再分别删除 MySQL 和 ES（`deleteByIds`），不要各自独立扫描。`rebuildConversationMemories` 中必须先 LLM 提取成功再删除旧数据，避免提取失败导致旧数据丢失。
- 复杂业务逻辑不要塞进 Controller；Service / Workflow / Agent 承担业务编排。详见 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。
- MySQL 主链路继续使用 MyBatis-Flex，不要平行引入 JPA、MyBatis-Plus 或另一套数据访问主框架。详见 [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)。

## 6. 本地开发及验证流程

1. 改动代码或文档。
2. 编译检查：`./mvnw -q -DskipTests compile`。
3. 启动本地服务：`./mvnw spring-boot:run`。
4. 登录验证：访问 `http://localhost:8081/api/doc.html` 或调用 `/api/user/login`。
5. 前端联调：启动 `cloud-cold-frontend`，确认 Cookie 携带、SSE 响应头和知识库 / HITL / 长期记忆主链路。

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

日志默认输出到控制台；更多排查点见 [docs/development.md](docs/development.md)。

## 7. 质量检查

| 检查项 | 命令 | 说明 |
| --- | --- | --- |
| 编译 | `./mvnw -q -DskipTests compile` | 当前最小验证 |
| 测试 | `./mvnw test` | 运行后端测试 |
| 打包 | `./mvnw clean package` | 生成可运行 JAR |
| 接口文档 | `http://localhost:8081/api/doc.html` | 启动后人工检查 |

## 8. 参考项目约定

参考优先级：

1. 当前仓库真实代码和 `application.yml`
2. `cloud-cold-frontend` 的实际调用方式
3. [docs/design-docs/ref-backend-architecture.md](docs/design-docs/ref-backend-architecture.md)
4. Spring Boot / Spring AI / MyBatis-Flex 官方约定

不要用脚手架默认习惯覆盖当前仓库已经形成的主链路约定。

## 9. 文档导航

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 人类快速上手和项目概览 |
| [docs/architecture.md](docs/architecture.md) | 分层架构、核心链路、接口概览、长期记忆架构 |
| [docs/development.md](docs/development.md) | 本地环境、配置、启动、验证、排查 |
| [docs/design-docs/ref-backend-architecture.md](docs/design-docs/ref-backend-architecture.md) | 参考项目架构说明 |
| [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md) | 后端组件、Tool、Skill、文档、长期记忆模式 |
