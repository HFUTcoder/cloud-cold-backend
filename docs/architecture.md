# 后端架构

本文说明 `cloud-cold-agent` 的分层架构、核心模块和主要业务链路。AI 工具的简短入口见根目录 [AGENTS.md](../AGENTS.md)。

## 分层架构

```text
Controller 层
  -> Service / Workflow / Agent 编排层
    -> 领域服务、Tools、Memory、Document pipeline
      -> Mapper、Redis、Elasticsearch、MinIO、OpenAI 兼容模型
```

- `controller/`：HTTP 协议和 SSE 出口，做登录态校验、请求参数接入和统一响应。
- `service/impl/`：绝大多数业务编排和资源归属校验。
- `agent/`：`SimpleReactAgent` 和 `PlanExecuteAgent` 两套执行器。
- `workflow/skill/`：Skill 绑定识别、候选发现、内容加载、执行计划和增强问题。
- `document/`：文档读取、清洗、切分和索引前处理。
- `tools/`：Agent 可调用工具；是否进入主链路取决于 `ToolRegistrationConfig`。
- `memory/`：聊天记忆落库与长期记忆触发。
- `service/usermemory/`：长期记忆召回、提取、重建和宠物状态。
- `config/`：Web 序列化、Session、MinIO、ES、Tool、配置属性。

## 核心运行事实

- `AgentServiceImpl` 当前注入 `@Qualifier("commonTools")`，而不是 `allTools`。
- `commonTools` 只包含 `search` 和 `execute_skill_script`。
- 知识库问答主链路是进入 Agent 前的服务层预检索，不是 Agent 运行时调用 RAG tool。
- 文档读取策略当前只有 `PdfMultimodalProcessor` 能真正命中，因此只支持 PDF。
- PDF 入库会同时处理正文文本和 PDF 图片描述，采用两级分块模型（父块 `PARENT` + 子块 `TEXT`），不再使用 `IMAGE_DESCRIPTION` chunk。
- `POST /document/upload` 同步完成上传、解析、抽图、索引和状态更新。
- `WebConfig` 把 `Long` / `long` 统一序列化成 JSON 字符串。
- 长期记忆在对话前召回、对话后触发整理，并有整点任务兜底。

## Agent 调用链路

`POST /api/agent/call` 的主要流程：

1. `AgentController` 校验登录态，必要时自动创建会话。
2. 创建 `SseEmitter`，统一事件名为 `agent`。
3. `AgentServiceImpl.call(...)` 触发会话活跃时间更新和首问标题生成。
4. `SkillWorkflowService.preprocess(...)` 执行 Skill 工作流预处理。
5. `KnowledgePreprocessService.preprocess(...)` 对绑定知识库的会话做混合检索。
6. `UserLongTermMemoryPreprocessService.preprocess(...)` 召回长期记忆并生成运行时 prompt。
7. 如 Skill 执行计划缺少必填参数，会短路返回 `final_answer`。
8. `fast` 路由到 `SimpleReactAgent`。
9. `thinking` / `expert` 路由到 `PlanExecuteAgent`。
10. 输出 `thinking_step`、`assistant_delta`、`final_answer`、`hitl_interrupt`、`knowledge_retrieval`、`error` 等事件。

`POST /api/agent/resume` 的主要流程：

1. 前端先调用 `/hitl/checkpoint/resolve` 处理审批结果。
2. `AgentServiceImpl.resume(...)` 根据 `interruptId` 找到 checkpoint。
3. 反查会话所属用户和 Agent 类型。
4. 当前只有 `PlanExecuteAgent` 支持恢复执行。

## Skill 工作流

工作流图定义在 `workflow/skill/config/SkillWorkflowConfig.java`，节点顺序是：

1. `loadBoundSkills`
2. `loadConversationHistory`
3. `recognizeBoundSkills`
4. `discoverCandidateSkills`
5. `loadSkillContents`
6. `buildSkillRuntimeContext`

关键输出包括：

- `selectedSkills`
- `selectedSkillContexts`

`execute_skill_script` 是当前最重要的 Skill 执行入口，也是默认 HITL 拦截工具。

## 知识库与文档链路

会话绑定知识库后，进入 Agent 前会先做预检索：

1. `KnowledgePreprocessServiceImpl.preprocess(...)`
2. `KnowledgeService.hybridSearch(userId, knowledgeId, question)`
3. 命中文本 chunk 拼入增强问题。
4. 命中图片描述 chunk 解析为可访问图片 URL。
5. 先发送 `knowledge_retrieval` SSE 事件。
6. 图片暂存到 `ChatMemoryPendingImageBindingService`。
7. 助手消息落库时再回绑历史消息和图片。

PDF 上传入库链路：

1. 校验知识库归属。
2. 原始 PDF 上传到 MinIO。
3. 写入 `knowledge_document`，状态从 `PENDING` 切到 `INDEXING`。
4. 临时落盘。
5. `DocumentReaderFactory` 选择 `PdfMultimodalProcessor`。
6. 提取正文文本。
7. 抽取 PDF 图片并调用多模态模型生成描述。
8. 正文按段落切分父块（`PARENT`），再按 200 字符切分子块（`TEXT`）。
9. 图像描述注入文本原位后，图像 ID 存入父块 `metadata.imageIds`。
10. 父块 + 子块写入关键词索引 `rag_docs`。
11. 子块写入向量索引 `rag_docs_vector`。
12. 成功置为 `INDEXED`；失败清理索引、图片记录并置为 `FAILED`。

## HITL

默认配置：

- `cloudcold.hitl.enabled=true`
- `cloudcold.hitl.intercept-tool-names=[execute_skill_script]`

运行流程：

1. `PlanExecuteAgent` 命中需要人工确认的工具。
2. `HitlCheckpointService` 创建 `hitl_checkpoint`。
3. SSE 推送 `hitl_interrupt`。
4. 前端调用 `/hitl/checkpoint/resolve`。
5. 前端调用 `/agent/resume`。
6. `HitlResumeServiceImpl` 消费审批结果并恢复执行。

## 长期记忆 / 宠物记忆

长期记忆不是孤立模块，已经接入主链路：

1. 对话前 `UserLongTermMemoryPreprocessServiceImpl` 召回相关记忆。
2. 召回结果生成运行时 prompt 注入 Agent。
3. 助手最终回答落库后，`MysqlChatMemoryRepository` 通知 `UserLongTermMemoryService`。
4. 同一会话累计达到 `trigger-rounds=5` 的新增完整轮次后异步整理。
5. `UserLongTermMemoryScheduler` 每小时兜底扫描待处理会话。
6. `/userMemory/*` 暴露宠物状态、记忆列表、重建、改名、删除接口。

## 主要接口

接口统一前缀为 `/api`。

- 用户：`/user/register`、`/user/login`、`/user/get/login`、`/user/logout`
- 会话：`/chatConversation/create`、`/chatConversation/list/my`、`/chatConversation/get`、`/chatConversation/update/skills`、`/chatConversation/update/knowledge`、`/chatConversation/delete`
- 历史：`/chatMemory/history/list/conversation`、`/chatMemory/history/list/user`、`/chatMemory/history/list/user/conversations`、`/chatMemory/history/delete`
- Agent：`/agent/call`、`/agent/resume`
- HITL：`/hitl/checkpoint/get`、`/hitl/checkpoint/latest`、`/hitl/checkpoint/resolve`
- Skill：`/skill/list`、`/skill/meta/{skillName}`、`/skill/{skillName}`、`/skill/resource`、`/skill/script/execute`
- 知识库：`/knowledge/create`、`/knowledge/get`、`/knowledge/update`、`/knowledge/delete`、`/knowledge/list/page/my`、`/knowledge/*-search`
- 文档：`/document/upload`、`/document/get`、`/document/preview-url`、`/document/delete`、`/document/list/by/knowledge`
- 长期记忆：`/userMemory/pet/state`、`/userMemory/list`、`/userMemory/rebuild`、`/userMemory/rename`、`/userMemory/delete`
- 调试 / 辅助：`/files/*`、`/es/*`

## 前后端契约

后端如果修改以下内容，通常需要同步 `cloud-cold-frontend`：

- SSE 事件名、事件类型和 payload 字段。
- Agent 模式值。
- `conversationId`、`interruptId` 语义。
- 会话绑定 Skill / 知识库字段。
- 聊天历史里的 `retrievedImages`。
- HITL checkpoint 和 resolve payload。
- `Long -> string` 的 JSON 序列化策略。
