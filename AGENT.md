# AGENT.md

本文件面向 Claude Code、Codex、Cursor 等 AI 编程工具。目标不是重复 README，而是帮助新的 AI 工具快速建立“这个仓库真实怎么工作”的上下文，避免只看依赖名、目录名或脚手架习惯做错误推断。

## 1. 项目定位

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端项目。当前主线不是“泛 Spring Boot 管理后台”，而是下面 5 条能力的组合：

- 会话式 AI Agent 对话
- Skill 工作流预处理与脚本执行
- HITL 人工审批与恢复执行
- 知识库 PDF 入库、检索、图片回显
- 用户长期记忆 / 宠物记忆

## 2. 必须先知道的事实

### 2.1 当前不是“所有 Controller 都是稳定产品接口”的项目

前端主链路主要依赖：

- `/agent/*`
- `/chatConversation/*`
- `/chatMemory/history/*`
- `/hitl/checkpoint/*`
- `/knowledge/create|get|update|delete|list/page/my`
- `/document/upload|get|preview-url|delete|list/by/knowledge`
- `/user/register|login|get/login|logout`
- `/skill/list`
- `/userMemory/*`

更偏调试、底层或历史保留的接口包括：

- `/es/*`
- `/files/*`
- `/knowledge/write`
- `/document/create`
- `/document/update`
- `/document/list/page/my`
- 大部分管理员用户接口

### 2.2 Agent 主工具池当前只有 2 个

不要因为仓库里有很多 `BaseTool` 子类，就默认认为它们都会进入主执行链。

当前 `AgentServiceImpl` 注入的是：

- `@Qualifier("commonTools")`

而 `ToolRegistrationConfig.commonTools()` 当前只注册了：

- `search`
- `execute_skill_script`

这意味着：

- `tools/rag/*` 虽然存在，但当前不会被 `SimpleReactAgent` / `PlanExecuteAgent` 直接调用
- `allTools` Bean 目前不是 AgentService 的实际注入来源

### 2.3 会话绑定知识库后的检索不是“主工具调用”，而是进入 Agent 前的服务层预处理

当前知识库问答链路是：

1. `KnowledgePreprocessService.preprocess(...)`
2. `KnowledgeService.hybridSearch(...)`
3. 组装增强问题
4. 如命中图片，先发 `knowledge_retrieval` 事件
5. 再把增强问题交给 Agent

因此：

- 不要把“仓库里有 RAG tool”误判成“当前知识库问答靠 tool 调用完成”
- 如果你修改知识库问答体验，优先看 `KnowledgePreprocessServiceImpl`

### 2.4 文档入库当前只支持 PDF

不要因为依赖里有 `tika` 或 `spring-ai-pdf-document-reader` 就假设项目已经支持 Word、Markdown、TXT 等多种格式。

当前 `DocumentReaderFactory` 最终能选中的真正实现只有：

- `PdfMultimodalProcessor`

所以：

- 当前真正支持入库的文档类型只有 `PDF`
- 前端上传控件也只允许选 PDF
- 如果要扩展别的格式，需要同时补：
  - 新的 `DocumentReaderStrategy`
  - 文档说明
  - 前端上传限制

### 2.5 PDF 入库是“文本 chunk + 图片描述 chunk”双通道索引

这个细节很容易被误写。

当前真实行为是：

- `PdfMultimodalProcessor` 提取正文文本
- 同时抽取 PDF 图片，调用多模态模型生成描述
- 正文文本走 `TEXT` chunk
- 图片描述在 `KnowledgeService.buildImageDescriptionChunks(...)` 中单独生成 `IMAGE_DESCRIPTION` chunk
- 最后统一写入关键词索引和向量索引

因此你修改入库链时，不能只盯：

- 文本切分
- 向量索引

还必须同时考虑：

- MinIO 原始文档
- MinIO 图片对象
- `knowledge_document_image`
- `IMAGE_DESCRIPTION` chunk

### 2.6 `POST /document/upload` 当前是同步入库，不是异步任务

这一点很重要，因为很多人看到“上传 + 索引状态字段”会下意识以为这是后台异步任务。

当前真实行为是：

- `KnowledgeDocumentIngestionServiceImpl.uploadDocument(...)` 在单次请求里完成上传、解析、抽图、建索引、状态更新
- 接口返回时，文档已经进入 `INDEXED` 或 `FAILED`

所以：

- 不要把它当成任务队列来写文档
- 不要在前端或测试里默认“上传成功后再轮询状态”

### 2.7 Web 层把 `Long` 统一序列化成字符串

`config/WebConfig.java` 对 `Long` / `long` 做了统一 `ToStringSerializer`。

这意味着：

- 后端很多 `id` 字段在 JSON 里是字符串
- 前端当前很多 `src/types/*` 仍把它们写成 `number`
- 这是当前项目现状，不是可以忽略的小细节

如果你修改这个行为，必须把它视为前后端契约级改动。

### 2.8 当前前端只暴露两个模式

后端支持：

- `fast`
- `thinking`
- `expert`

前端首页当前只显示：

- `fast`
- `thinking`

所以：

- 不要把“前端没看到 `expert`”误判为“后端已删除 `expert`”
- 也不要在文档里写“当前前端支持 `expert` 切换”

### 2.9 当前前端 UI 只支持单 Skill 绑定，但后端底层支持多 Skill

后端会话字段是：

- `selectedSkillList`

关系表也支持多个 Skill。

但当前前端 `HomeView.vue` 的交互是：

- 绑定时只传 `[skillName]`
- 页面只显示一个 `selectedSkillName`

如果你修改 Skill 绑定逻辑，需要区分：

- 后端底层能力
- 前端当前实际 UI 能力

### 2.10 聊天历史只持久化最终回答，不持久化思考过程

当前历史消息回放来源是：

- `ChatMemoryHistory`
- `ChatMemoryHistoryImageRelation`

它能回放：

- 用户消息
- 助手最终回答
- 助手消息命中的知识库图片

它不会回放：

- 流式思考过程

不要把前端实时展示的 thinking 区，误判成已经落库的长期历史。

### 2.11 长期记忆已经接入主链路，不是孤立模块

这是当前仓库最容易被低估的一条能力。

真实情况是：

- 对话前会做长期记忆召回，产出运行时 prompt
- 助手回答持久化后会累计待处理轮次
- 达到阈值后会异步整理长期记忆
- 每小时还有一个定时任务兜底处理待整理会话
- 前端已经有 `PetMemoryWidget.vue` 接 `/userMemory/*`

所以：

- 不要把 `service/usermemory/*` 当成未接入实验代码
- 也不要在 README / AGENT 里漏掉这条主线

### 2.12 配置类默认值和当前运行值不完全相同

有些 `@ConfigurationProperties` 类的默认值，与仓库当前 `application.yml` 的运行值并不一致。

例如：

- `SearchProperties.mock.enabled` 代码默认是 `false`，当前运行值是 `true`
- `LongTermMemoryProperties.triggerRounds` 代码默认是 `6`，当前运行值是 `5`
- `LongTermMemoryProperties.defaultPetName` 代码默认是 `小冷`，当前运行值是 `小云`

因此：

- 描述当前行为时优先以 `application.yml` 为准
- 讨论“框架默认值”时再回看配置类

### 2.13 当前存在一些未统一加鉴权的接口

这些接口当前没有显式 `@AuthCheck`：

- `/skill/*`
- `/files/*`
- `/es/*`
- `/user/get/vo`

它们里有些是前端当前会直接访问的，有些更像调试接口。

因此：

- 不要默认把它们写成“必须登录后访问”
- 也不要在未评估前直接依赖它们是公开接口的现状

## 3. 快速事实

- 语言与运行时：Java 21
- 构建工具：Maven Wrapper（`./mvnw`）
- Spring Boot：3.5.13
- 默认端口：`8081`
- 接口前缀：`/api`
- 默认 Profile：`local`
- 应用入口：`src/main/java/com/shenchen/cloudcoldagent/CloudColdAgentApplication.java`
- 根包名：`com.shenchen.cloudcoldagent`
- 应用启用了 `@EnableScheduling`

## 4. 基础设施与配置

### 4.1 当前依赖的基础设施

- MySQL：用户、会话、聊天记忆、HITL、知识库、文档元数据、长期记忆
- Redis：Spring Session、长期记忆宠物元数据
- Elasticsearch：关键词索引 `rag_docs`
- Elasticsearch Vector Store：向量索引 `rag_docs_vector`
- 长期记忆关键词索引：`user_long_term_memory_docs`
- 长期记忆向量索引：`user_long_term_memory_vector`
- MinIO：原始文档与 PDF 中提取的图片
- OpenAI 兼容模型：
  - 聊天模型
  - Embedding 模型
  - PDF 多模态模型

### 4.2 关键配置组

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
- `cloudcold.long-term-memory.*`

### 4.3 与当前实现强绑定的配置事实

- `cloudcold.search.mock.enabled=true` 是当前仓库运行值
- `cloudcold.hitl.enabled=true` 是当前仓库运行值
- 当前默认只拦截 `execute_skill_script`
- MinIO bucket 不存在时会自动创建
- MinIO bucket 会被设置成公共读

## 5. 代码地图

### 5.1 主链路最关键的文件

- `controller/AgentController.java`
  - `/agent/call` 和 `/agent/resume` 的 SSE 出口
- `service/impl/AgentServiceImpl.java`
  - Agent 总编排、模式路由、Skill 预处理、知识库预处理、长期记忆预处理、HITL resume 入口
- `agent/SimpleReactAgent.java`
  - `fast` 模式实现
- `agent/PlanExecuteAgent.java`
  - `thinking` / `expert` 模式实现
- `workflow/skill/config/SkillWorkflowConfig.java`
  - Skill 工作流图
- `workflow/skill/service/impl/SkillWorkflowServiceImpl.java`
  - Skill 预处理入口
- `service/impl/KnowledgePreprocessServiceImpl.java`
  - 会话绑定知识库后的预检索与增强问题构造
- `service/impl/KnowledgeDocumentIngestionServiceImpl.java`
  - 文档上传、MinIO、入库状态推进
- `service/impl/KnowledgeServiceImpl.java`
  - 文档切分、图片描述 chunk、关键词检索、向量检索、混合检索
- `document/extract/reader/PdfMultimodalProcessor.java`
  - 当前唯一文档读取策略
- `service/usermemory/impl/UserLongTermMemoryServiceImpl.java`
  - 长期记忆写入、召回、重建、宠物状态
- `service/usermemory/impl/UserLongTermMemoryPreprocessServiceImpl.java`
  - 对话前长期记忆召回
- `job/UserLongTermMemoryScheduler.java`
  - 整点处理长期记忆
- `config/ToolRegistrationConfig.java`
  - Agent 实际工具池注册点
- `config/SkillConfig.java`
  - 本地 Skill 目录加载
- `config/WebConfig.java`
  - `Long -> string` 序列化

### 5.2 常用目录职责

- `controller/`
  - HTTP 接口和 SSE 接口
- `service/impl/`
  - 绝大多数真实业务逻辑
- `agent/`
  - Agent 实现
- `workflow/skill/`
  - Skill 工作流图、节点、状态对象
- `tools/`
  - Tool 定义
- `memory/`
  - 聊天记忆 MySQL 持久化
- `document/`
  - 文档解析、清洗、切分、向量存储
- `service/usermemory/`
  - 长期记忆与宠物记忆
- `config/properties/`
  - `@ConfigurationProperties`

## 6. 建议阅读顺序

### 6.1 看 Agent / SSE / HITL 问题

1. `AgentController`
2. `AgentServiceImpl`
3. `SimpleReactAgent` 或 `PlanExecuteAgent`
4. `SkillWorkflowServiceImpl`
5. `KnowledgePreprocessServiceImpl`
6. `UserLongTermMemoryPreprocessServiceImpl`
7. `HitlCheckpointServiceImpl` / `HitlResumeServiceImpl`
8. `ToolRegistrationConfig`

### 6.2 看知识库 / 文档问题

1. `DocumentController`
2. `KnowledgeDocumentIngestionServiceImpl`
3. `KnowledgeServiceImpl`
4. `PdfMultimodalProcessor`
5. `ElasticSearchServiceImpl`
6. `MinioServiceImpl`

### 6.3 看会话 / 登录 / 历史消息问题

1. `UserController` / `UserServiceImpl`
2. `AuthInterceptor`
3. `ChatConversationController` / `ChatConversationServiceImpl`
4. `ChatMemoryHistoryController` / `ChatMemoryHistoryServiceImpl`
5. `MysqlChatMemoryRepository`

### 6.4 看长期记忆 / 宠物记忆问题

1. `UserLongTermMemoryController`
2. `UserLongTermMemoryServiceImpl`
3. `UserLongTermMemoryPreprocessServiceImpl`
4. `UserLongTermMemoryStoreImpl`
5. `UserLongTermMemoryScheduler`

## 7. 当前主链路

### 7.1 `/agent/call`

当前链路是：

1. Controller 校验登录态
2. 无 `conversationId` 时自动创建会话
3. 创建 `SseEmitter`
4. `AgentServiceImpl.call(...)`
5. `touchConversation(...)`
6. `generateTitleOnFirstMessage(...)`
7. Skill 工作流预处理
8. 知识库预检索预处理
9. 长期记忆预处理
10. Skill 缺参阻塞时直接返回 `final_answer`
11. 按模式路由到具体 Agent
12. SSE 持续输出

额外事实：

- 会话标题当前是“首条消息前 5 个字符”
- 缺参阻塞时，会把用户问题和短路回复一并写入聊天记忆
- 如知识库命中图片，会先发 `knowledge_retrieval`

### 7.2 `/agent/resume`

当前链路是：

1. 前端先 `resolve checkpoint`
2. 再调 `/agent/resume`
3. `AgentServiceImpl.resume(...)`
4. 通过 `interruptId` 找到 checkpoint
5. 从 `user_conversation_relation` 反查 `userId`
6. 当前只有 `PlanExecuteAgent` 支持 resume

### 7.3 Skill 工作流

当前节点顺序固定为：

1. `loadBoundSkills`
2. `loadConversationHistory`
3. `recognizeBoundSkills`
4. `discoverCandidateSkills`
5. `loadSkillContents`
6. `buildSkillExecutionPlans`
7. `buildEnhancedQuestion`

### 7.4 文档入库

当前顺序是：

1. 校验知识库归属
2. 上传原文件到 MinIO
3. 写入 `knowledge_document`
4. `PENDING -> INDEXING`
5. 临时落盘
6. PDF 文本解析
7. PDF 图片抽取 + 多模态描述
8. 正文生成 `TEXT` chunk
9. 图片描述生成 `IMAGE_DESCRIPTION` chunk
10. 写关键词索引
11. 写向量索引
12. `INDEXED` 或 `FAILED`

### 7.5 长期记忆

当前顺序是：

1. 对话前召回相关记忆
2. 生成运行时 prompt 注入 Agent
3. 助手回答落库后累计待处理轮次
4. 达到阈值后异步触发处理
5. 整点任务兜底处理遗漏会话
6. 前端宠物组件读取状态、展示摘要、支持手动重建

## 8. 开发规范与修改原则

### 8.1 依赖注入与代码风格

- Spring 组件继续使用显式构造函数注入
- 不要重新引入字段注入
- 不要把复杂业务逻辑塞进 Controller
- DTO / VO / Entity / Record 继续沿用现有目录分层
- 日志统一走 `Slf4j`

### 8.2 数据访问规范

- MySQL 访问继续使用 `MyBatis-Flex`
- 不要平行引入 JPA、JdbcTemplate、MyBatis-Plus 作为新的主链路
- 逻辑删除、关联清理、资源归属校验必须保持一致

### 8.3 会影响前端的改动要特别谨慎

以下改动默认都要联动 `cloud-cold-frontend`：

- SSE 事件类型和字段
- `conversationId` / `interruptId` 语义
- Agent 模式值
- 聊天历史里的 `retrievedImages`
- Skill 绑定返回字段
- 会话绑定知识库字段
- `Long -> string` 序列化策略
- HITL payload 结构
- 长期记忆 / 宠物状态结构

### 8.4 Tool 相关规则

- 新增 Tool 时优先继承 `BaseTool`
- 如果只注册成 Spring Bean，但没有加入 `commonTools`，Agent 主链路不会用到它
- 如果 Tool 需要 HITL 审批，把工具名加入 `cloudcold.hitl.intercept-tool-names`

### 8.5 Skill 相关规则

- 当前 Skill 预处理统一走 `workflow/skill/*`
- 不要在 Controller 或 Agent 里手写一套绕过工作流的 Skill 识别逻辑
- `execute_skill_script` 当前是最重要的 Skill 执行入口，也是默认 HITL 拦截点
- `SkillConfig` 当前从文件系统 `src/main/resources/skills` 加载本地 Skill，这对启动方式有真实约束

### 8.6 知识库 / 文档相关规则

- 当前实现只支持 PDF；扩展文档类型时要同步前后端和文档
- 修改上传链路时，必须同时考虑：
  - MySQL 文档状态
  - MinIO 原文件
  - MinIO 图片对象
  - `knowledge_document_image`
  - Elasticsearch 关键词索引
  - Elasticsearch 向量索引
- 删除逻辑不能只删数据库
- 如果改知识库问答效果，优先看 `KnowledgePreprocessServiceImpl`，不是只看 Agent tools

### 8.7 长期记忆相关规则

- 对话前记忆召回入口在 `UserLongTermMemoryPreprocessServiceImpl`
- 对话后记忆累计触发入口在 `MysqlChatMemoryRepository`
- 不要手动在 Controller 层补一套长期记忆触发逻辑
- 如果改长期记忆 prompt 或阈值，要同时核对：
  - `application.yml`
  - `LongTermMemoryProperties`
  - 前端宠物状态展示

### 8.8 登录与权限规则

- 主业务接口大多依赖 `@AuthCheck`
- 登录态来自 `HttpSession + Redis`
- Service 层仍需要继续校验资源归属
- Skill / File / ES / `user/get/vo` 这几组接口当前没有统一加 `@AuthCheck`，改动前先判断是要保留现状还是要统一收紧

## 9. 高风险误判提醒

- 不要把 `allTools` 当成 Agent 实际工具池
- 不要把仓库里的 RAG tool，误判成当前知识库主链依赖
- 不要把依赖里的 Tika / PDF Reader 当成“已经支持多格式文档”
- 不要把 PDF 图片描述误写成“已经直接混入正文统一切块”
- 不要把文档上传当成异步任务队列
- 不要忽略 `Long` 被序列化成字符串这件事
- 不要把前端的单 Skill UI，误判成后端只能绑定一个 Skill
- 不要把前端没暴露 `expert`，误写成后端也没有 `expert`
- 不要把长期记忆模块当成未接入实验代码
- 不要在没同步前端的情况下修改 SSE 协议
- 不要假设打成 JAR 后 Skill 加载还能天然工作

## 10. 你准备修改前，最好先确认这几个问题

1. 这个改动会不会影响 `cloud-cold-frontend` 的 SSE / Cookie / ID 类型行为？
2. 新增的 Tool 是否真的需要给 Agent 主链路使用？
3. 这次改动是不是默认假设了“知识库问答靠主工具调用”，但当前其实是服务层预处理？
4. 这次改动会不会导致 MySQL、ES、MinIO 三者状态不一致？
5. 这次改动是否改变了 `Long -> string` 的 Web 返回行为？
6. 这次改动会不会影响长期记忆召回、重建阈值或宠物状态展示？
7. 如果你改了启动方式或部署方式，`src/main/resources/skills` 的本地文件加载还成立吗？
