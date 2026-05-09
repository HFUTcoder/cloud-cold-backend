# AI 图像生成提示词

以下是为 `cloud-cold-agent` 后端项目生成技术架构图、业务流程图等图像的 AI 提示词。每个提示词独立可用，建议使用支持技术图表生成的 AI 图像模型（如 DALL-E 3、Midjourney v6+、Stable Diffusion 3 等）。

> **使用说明**：将提示词原文粘贴到图像生成工具的 prompt 输入框即可。提示词中英文混杂的技术术语（如 Agent、SSE、HITL 等）是有意保留的，因为 AI 模型对这些术语的英文原名理解更准确。

---

## 一、系统技术架构全景图

```
生成一张专业的后端技术架构图，布局为自上而下的分层架构，白色或浅灰背景，使用深蓝（#1a56db）、墨绿（#059669）、橙色（#ea580c）、紫色（#7c3aed）四种主色调区分不同层级。

顶层标签「前端层」包含一个矩形框 "cloud-cold-frontend (Vue 3 + TypeScript + Vite)"，箭头向下连接。

第二层标签「接入层 (Controller Layer)」，包含横向排列的 5 个矩形框：AgentController、SkillController、KnowledgeController、DocumentController、UserLongTermMemoryController。每个框下方标注对应的 HTTP 协议：SSE / REST。

第三层标签「服务编排层 (Service / Workflow / Agent Layer)」，分三列并排：
- 左列「Skill 工作流」包含垂直排列的 6 个小框：loadBoundSkills → loadConversationHistory → recognizeBoundSkills → discoverCandidateSkills → loadSkillContents → buildSkillRuntimeContext，用箭头连接。
- 中列「Agent 编排」包含 1 个大方框 "AgentServiceImpl"，内部标注 Skill 预处理 → 知识库预检索 → 长期记忆召回 → Agent 路由。
- 右列「Agent 执行器」包含 2 个横向框：SimpleReactAgent (标注 fast 模式) 和 PlanExecuteAgent (标注 thinking/expert 模式)。

第四层标签「领域服务层」，包含 3 个分组框：
- 「知识库服务」：KnowledgePreprocessService、KnowledgeDocumentIngestionService、DocumentReaderStrategy
- 「长期记忆服务」：UserLongTermMemoryPreprocessService、UserLongTermMemoryService、UserLongTermMemoryStore
- 「HITL 服务」：HitlCheckpointService、HitlExecutionService、HitlResumeService

第五层标签「基础设施层」，包含 6 个图标式方框：MySQL（蓝色）、Elasticsearch（黄色）、Redis Session（红色）、MinIO Object Storage（青色）、GraalVM Python Runtime（深绿）、DashScope LLM API（紫色）。

左侧添加竖直色条标注：鉴权切面 (AuthInterceptor)、分布式锁切面 (DistributeLockAspect)、RateLimiter。

箭头从顶层逐层向下流动。图片比例 16:9，分辨率 2560×1440，风格为现代企业级架构图，线条清晰、字体使用无衬线体、所有文字可读。
```

**预期输出**：一张完整的后端分层架构图，展示前端到基础设施的 5 层结构。

---

## 二、Agent 请求处理流程图

```
生成一张详细的业务流程图，展示从用户发起到 SSE 响应完成的 Agent 请求处理全链路。背景为白色，使用深蓝（#1a56db）作为主色调，关键节点用圆角矩形，条件分支用菱形，SSE 事件用绿色填充框。

流程图自上而下：

START 节点（绿色圆角，文字「用户发送 Agent 请求」）

箭头指向矩形「AgentController.call() — 校验登录态 (@AuthCheck)」

箭头指向矩形「解析 agentCallRequest：question、mode、conversationId」

箭头指向矩形「AgentServiceImpl.call()」

箭头指向矩形「resolveConversationId — 必要时自动创建会话」

箭头指向矩形「touchConversation — 更新会话活跃时间」

箭头指向矩形「generateTitleOnFirstMessage — 首问自动生成对话标题」

箭头指向矩形「skillWorkflowService.preprocess() — Skill 工作流预处理」
此框右侧添加标注注解：「6 节点顺序图：loadBoundSkills → loadConversationHistory → recognizeBoundSkills → discoverCandidateSkills → loadSkillContents → buildSkillRuntimeContext」

箭头指向矩形「knowledgePreprocessService.preprocess() — 知识库预检索」
此框右侧添加标注注解：「hybridSearch (keyword + vector) → KnowledgePrompts 增强问题 → 提取图片 MinIO URL」

箭头指向矩形「userLongTermMemoryPreprocessService.preprocess() — 长期记忆召回」
此框右侧添加标注注解：「向量相似度召回 topK=5 → 最多 4 条拼入 system prompt」

箭头指向矩形「buildRuntimeSystemPrompt — 组装运行时 System Prompt」
此框右侧标注三个来源：「长期记忆 prompt + Skill 上下文 prompt + 知识库绑定 prompt」

箭头指向矩形「registerPendingImages — 注册待绑定知识库图片」

箭头指向矩形「buildPreAgentFlux — 构建 knowledge_retrieval SSE 事件」

箭头指向菱形「Agent 模式路由 (AgentModeEnum)」

菱形分出三个分支：
- 左侧分支（标签 FAST）→ 矩形「SimpleReactAgent.stream()」→ 标注「ReAct 循环 + Sinks.Many SSE 流式输出」
- 中间分支（标签 THINKING）→ 矩形「PlanExecuteAgent.stream()」→ 标注「Plan → Execute → Critique → Summarize」
- 右侧分支（标签 EXPERT）→ 矩形「PlanExecuteAgent.stream()」→ 同 THINKING

三个分支汇合到矩形「SSE 事件流返回」

矩形箭头指向多个绿色 SSE 事件框并排：
「thinking_step」(蓝色) → 「assistant_delta」(蓝色) → 「final_answer」(绿色) → 「hitl_interrupt」(橙色，虚线边框表示条件触发) → 「knowledge_retrieval」(紫色) → 「error」(红色)

箭头指向 END 节点（绿色圆角，文字「前端接收并渲染」）

底部添加图例：蓝色=正常流程、绿色=最终输出、橙色=中断、红色=异常、虚线=条件触发

图片比例 9:16（竖版为佳），分辨率 1440×2560，现代平面设计风格，所有文字使用中文标注。
```

**预期输出**：一张竖版完整流程图，展示从 HTTP 请求到 SSE 响应全链路 15+ 步骤。

---

## 三、PlanExecuteAgent 执行周期图

```
生成一张 Plan-Execute-Critique-Summarize 执行周期图，白色背景，使用深紫（#7c3aed）为主色调，循环布局。

中心放置标题「PlanExecuteAgent 执行周期」。

四个节点形成循环：

节点一（左上，深蓝色圆角矩形）「1. Plan 规划」
内部文字：「LLM 分析用户问题 + SkillRuntimeContext → 生成 List<PlanTask> → 每个 PlanTask 包含: id、toolName、arguments、order、summary → 按 order 字段分组（同 order 并行执行）」

箭头从 Plan 指向 Execute

节点二（右上，绿色圆角矩形）「2. Execute 执行」
内部文字：「按 order 分组循环执行 → 同 order 任务并行 (Semaphore 控制并发度默认3) → 执行前检查 HITL 拦截 → 命中 interceptToolNames 则中断 → 记录 ExecutedTaskSnapshot」
下方标注虚线箭头指向「HITL 中断 → 创建 hitl_checkpoint → SSE 推送 hitl_interrupt」

箭头从 Execute 指向 Critique

节点三（右下，橙色圆角矩形）「3. Critique 评审」
内部文字：「LLM 评估当前进度和执行结果 → 返回三种决策: → SUMMARIZE: 任务完成,进入总结 → CONTINUE: 继续下一轮 Plan → ASK_USER: 需要用户提供更多信息」
下方标注「当字数超过 5000 字符时,触发 Context Compression, LLM 压缩已有执行状态」

两个箭头从 Critique 出发：
- 「CONTINUE」回指到 Plan（形成循环，标注「maxRounds=5 限制」）
- 「SUMMARIZE」指向 Summarize

节点四（右下，紫色圆角矩形）「4. Summarize 总结」
内部文字：「ChatClient.stream() 流式输出最终答案 → 发出 final_answer SSE 事件」

右侧添加一个独立方框「HITL Resume 恢复」
内部文字：「POST /agent/resume → 反序列化 ResumeContext → 应用用户编辑的参数 → 跳过已消费 toolCallId → 继续 Execute 剩余任务」
虚线连接到此框与 Execute 节点

底部图例：蓝色=规划、绿色=执行、橙色=评审、紫色=总结、虚线=条件触发

图片比例 4:3，分辨率 2048×1536，现代技术图表风格。
```

**预期输出**：一张循环流程图，展示 PlanExecuteAgent 的四个阶段及 HITL 恢复机制。

---

## 四、知识库 PDF 入库流程图

```
生成一张知识库 PDF 文档从上传到索引建立完成的全链路流程图，白色背景，使用墨绿（#059669）作为主色调，纵向流程布局。

顶部 START 节点文字「用户调用 POST /document/upload」

箭头指向矩形「DocumentController.uploadDocument() — 校验知识库归属」

箭头指向矩形「knowledgeDocumentIngestionService.uploadDocument()」

箭头指向矩形「1. 原始 PDF 上传至 MinIO — bucket: cloud-cold」

箭头指向矩形「2. 写入 knowledge_document 表 — status: PENDING」

箭头指向矩形「3. 状态更新 — PENDING → INDEXING」

箭头指向矩形「4. PDF 临时落盘到本地」

箭头指向矩形「5. DocumentReaderFactory.selectStrategy() → PdfMultimodalProcessor」

此节点下方分出两个并行的子分支：

分支 A（蓝色，标签「正文提取」）：
小矩形「PDFBox / 文本提取 → DocumentCleaner 清洗 → TEXT chunk」

分支 B（橙色，标签「图片处理」）：
小矩形「抽取 PDF 内嵌图片 → qwen3-vl-plus 多模态模型生成描述 → IMAGE_DESCRIPTION chunk」

两个分支汇合到矩形「6. OverlapParagraphTextSplitter + ParentTextSplitter 文本切分」

箭头指向矩形「7. EmbeddingService — text-embedding-v4 生成向量 (1536 维)」

箭头指向矩形「8. StoreService — 写入 Elasticsearch 双索引」
此节点右侧标注：「关键词索引: rag_docs」「向量索引: rag_docs_vector (cosine 相似度)」

箭头指向矩形「9. 写入 knowledge_document_image 表 (图片 chunk 关联)」

箭头指向菱形「入库结果」

菱形分出两个分支：
- 成功分支（绿色）→ 矩形「状态更新为 INDEXED」
- 失败分支（红色）→ 矩形「清理索引 + 清理图片记录 + 状态回退为 FAILED」

两个分支汇合到 END 节点

右下角标注同步特性：「注意: 整个流程是同步的，接口返回时文档状态已为 INDEXED 或 FAILED」

底部列出引用的关键类和模型：DocumentReaderFactory, PdfMultimodalProcessor, text-embedding-v4, qwen3-vl-plus, MinIO

图片比例 9:16（竖版），分辨率 1440×2560，流程图风格清晰，所有步骤带编号。
```

**预期输出**：一张完整的 PDF 入库链路图，包含双分支（文本+图片）处理和同步特性说明。

---

## 五、知识库混合检索流程图

```
生成一张知识库混合检索（Hybrid Search）流程图，展示 Agent 调用前知识库预检索的完整过程。白色背景，使用青色（#0891b2）为主色调。

流程自上而下：

START: 「Agent 请求进入 AgentServiceImpl.call()」

箭头指向菱形「会话是否绑定了 selectedKnowledgeId？」

否分支（灰色虚线）→ 矩形「跳过知识库预检索，直接使用原始问题」

是分支（蓝色实线）→ 矩形「KnowledgePreprocessServiceImpl.preprocess(userId, conversation, question)」

箭头指向矩形「1. 提取 conversation 中的 selectedKnowledgeId 和 selectedKnowledgeIds 列表」

箭头分成两个并行搜索分支：

左分支（标签「关键词搜索 (BM25)」，蓝色）：
矩形「构建关键词查询 → ES keyword index: rag_docs → BM25 算法评分」

右分支（标签「向量搜索 (Vector)」，紫色）：
矩形「text-embedding-v4 向量化用户问题 (1536 维) → ES vector index: rag_docs_vector → cosine 相似度检索」

两个分支汇合到矩形「2. 合并去重 + 加权排序 → 最多 8 个结果片段 (chunk) → 总字符数上限 4000」

箭头指向菱形「命中 chunk 的类型？」

分出两个并行的处理分支：

左分支（蓝色，标签「TEXT chunk」）：
矩形「提取 chunk 文本 → KnowledgePrompts 构建增强问题 → 拼接到用户问题前」

右分支（紫色，标签「IMAGE_DESCRIPTION chunk」）：
矩形「解析 chunk 中的 imageId → 查询 knowledge_document_image 表 → MinIO 生成 presigned URL (7 天有效期) → 构建 RetrievedKnowledgeImage 列表」

两个分支汇合到矩形「3. 返回 KnowledgePreprocessResult」

此框标注三个输出字段：
- effectiveQuestion (增强后的问题)
- retrievedChunks (命中的文本片段)
- retrievedImages (命中的图片 URL 列表)

箭头指向矩形「4. 发送 knowledge_retrieval SSE 事件 — 包含 retrievedImages 在前端即时展示」

箭头指向矩形「5. ChatMemoryPendingImageBindingService.registerPendingImages() — 暂存图片等待助手消息落库时回绑」

箭头指向 END: 「进入 Agent 执行」

底部注释：「tools/rag/ 下的 3 个知识库检索工具不在 commonTools 中，Agent 运行时不可调用，知识库检索只走服务层预检索」

图片比例 9:16，分辨率 1440×2560。
```

**预期输出**：展示混合检索的双路搜索、结果合并、根据 chunk 类型分发的完整流程。

---

## 六、长期记忆系统架构图

```
生成一张长期记忆 / 宠物记忆系统的完整架构图，展示提取、存储、召回、API 四个维度的全貌。白色背景，使用暖橙色（#ea580c）作为主色调。

整体布局为四象限：

左上象限「提取 Pipeline (Extraction)」：
纵向流程：
1. 「MysqlChatMemoryRepository.saveAll()」→ 助手消息落库
2. 「通知 UserLongTermMemoryService」→ 累计轮次计数
3. 菱形「累计新增轮次 ≥ triggerRounds ? (默认 5)」
4. 「异步触发 LLM 结构化提取」→ UserLongTermMemoryPrompts
5. 「LLM 输出: List<UserLongTermMemoryExtractionItem>」→ memoryType, content, keywords, confidence
6. 「fallback 退化策略」→ 提取失败时直接保存原文摘要
7. 「MySQL + ES 双写」

右上象限「存储架构 (Storage)」：
三层存储垂直排列：
- 「MySQL (权威数据源)」: user_long_term_memory 表、user_long_term_memory_source_relation 表、user_long_term_memory_conversation_state 表
- 「Elasticsearch (检索加速)」:
  - 关键词索引 user_long_term_memory_docs
  - 向量索引 user_long_term_memory_vector (1536 维)
- 「Redis (快速状态)」: 宠物名称 petName、最后学习时间

三个存储层之间用双向箭头连接，标注「一致性保证」

左下象限「召回 Pipeline (Recall)」：
纵向流程：
1. 「UserLongTermMemoryPreprocessService.preprocess()」→ 在 AgentServiceImpl 中调用
2. 「userLongTermMemoryService.retrieveRelevantMemories()」→ 向量相似度召回 topK=5
3. 「相似度阈值过滤 (≥0.5)」→ 最多取 4 条 (maxPromptMemories)
4. 「生成 runtime system prompt」→ 每条最多 400 字符
5. 「拼入 buildRuntimeSystemPrompt」→ Agent 可见上下文

右下象限「定时任务与 API」：
- 「UserLongTermMemoryScheduler」→ 每小时整点扫描待处理会话 → 调用提取 Pipeline
- API 列表（竖排）：
  - GET /userMemory/pet/state — 宠物状态
  - GET /userMemory/list — 记忆列表
  - POST /userMemory/rebuild — 重建会话记忆
  - POST /userMemory/rename — 重命名宠物
  - POST /userMemory/delete — 删除记忆（MySQL + ES 同步删除）

中心放置宠物图标标注「宠物名称: 小云 (默认，可通过 rename 修改)」

底部图例说明双写语义：「MySQL→ES 先写 MySQL 后同步 ES」「删除时必须先查询 MySQL 获取 ID 列表，再分别删除 MySQL 和 ES」

图片比例 16:9，分辨率 2560×1440。
```

**预期输出**：一张完整的长期记忆四象限架构图，覆盖提取、存储、召回、API 四个维度。

---

## 七、HITL 人机协同流程图

```
生成一张 HITL (Human-In-The-Loop) 人工审批流程图，展示从 Agent 中断到用户审批再到恢复执行的完整交互流程。白色背景，使用橙色（#ea580c）作为主色调，泳道图风格。

流程图分为三个泳道（从上到下排列）：

泳道一「Agent 执行器 (PlanExecuteAgent)」：
- 节点：executeStructuredToolTask() → 检查 hitlInterceptToolNames.contains(toolName)
- 菱形：命中了被拦截的工具？默认拦截工具: [execute_skill_script]
- 未命中 → 正常执行工具并返回结果
- 命中 → 进入 HITL 流程

泳道二「HITL 服务层」：
- 节点：hitlCheckpointService.createCheckpoint() → 写入 hitl_checkpoint 表 (status: PENDING)
- 节点：HITLState 记录未消费的 toolCallId → 推入 ConcurrentHashMap<String, Set<Long>>
- 节点：构建 HitlCheckpointVO → 包含 toolName、原始参数等
- 节点：SSE 推送 hitl_interrupt 事件 → 包含 interruptId、toolName、arguments、摘要信息

泳道三「前端交互与恢复」：
- 节点：前端接收 hitl_interrupt 事件 → 中断标记 → 用户查看工具调用详情
- 菱形：用户审批决定
- 选项 A (APPROVED)：批准执行
- 选项 B (REJECTED)：拒绝执行
- 选项 C (EDIT)：编辑参数后批准
- 节点：POST /hitl/checkpoint/resolve → status 更新为 RESOLVED
- 节点：POST /agent/resume → 传入 interruptId
- 节点：HitlResumeServiceImpl 反序列化 ResumeContext
- 节点：PlanExecuteAgent.resume() → 恢复已保存状态
- 菱形：检查 toolCallId 是否已消费 (HITLState)
- 已消费 → 跳过，继续下一任务
- 未消费 → 执行工具 (使用用户编辑后的参数)
- 节点：继续 Execute 阶段的剩余任务
- 特殊情况：全部 APPROVED → 正常执行 | 全部 REJECTED → Agent 输出拒绝信息并停止

三泳道之间用带标签的箭头连接。

底部配置信息框：「cloudcold.hitl.enabled=true」「cloudcold.hitl.intercept-tool-names=[execute_skill_script]」「默认仅拦截 Python 脚本执行工具」

关键数据流标注：interruptId、toolCallId、checkpoint status (PENDING→RESOLVED→CONSUMED)

图片比例 16:9，分辨率 2560×1440，泳道图风格，三个泳道颜色分别为蓝、橙、绿。
```

**预期输出**：一张三泳道流程图，清晰展示 Agent、HITL 服务、前端三个角色的协作流程。

---

## 八、Skill 工作流 6 节点处理图

```
生成一张 Skill 工作流的 6 节点顺序处理图，展示从会话进入 Skill 预处理到输出 SkillRuntimeContext 的完整过程。白色背景，使用蓝色（#2563eb）作为主色调，横向从左到右排列。

整体布局：从左到右的 6 个节点，用粗实线箭头连接。

节点 1「loadBoundSkills」：
矩形，蓝色填充
内部文字：「获取会话已绑定的 Skill 列表 → 查询 ConversationSkillRelation 关联表 → 得到 List<Skill>」

节点 2「loadConversationHistory」：
矩形，蓝色填充
内部文字：「从 MysqlChatMemoryRepository 读取对话历史 → 获取最近对话上下文消息 → 作为后续 LLM 判断的依据」

节点 3「recognizeBoundSkills」：
矩形，蓝色填充，边框加粗标注「LLM 调用」
内部文字：「将已绑定 Skill 的元信息 + 对话历史提交给 LLM → LLM 判断哪些已绑定 Skill 与当前问题相关 → 输出相关 Skill 名称列表 (recognizedSkills)」

节点 4「discoverCandidateSkills」：
矩形，蓝色填充，边框加粗标注「LLM 调用」
内部文字：「将用户可用但未绑定的 Skill 元信息 + 对话历史提交给 LLM → LLM 判断是否有未绑定但相关的 Skill → 推荐候选 Skill 列表 (candidateSkills) → 输出 SkillCandidateListResult」

节点 5「loadSkillContents」：
矩形，蓝色填充
内部文字：「根据 recognizedSkills + candidateSkills → 读取对应 Skill 的 SKILL.md 文件内容 → FileSystemSkillRegistry → CachingSkillRegistry 缓存」

节点 6「buildSkillRuntimeContext」：
矩形，绿色填充（最终节点）
内部文字：「组装 SkillRuntimeContext 对象列表 → 每个 context 包含: skillName, skillContent, argumentSpecs, executionHints → 注入到 PlanExecuteAgent 的 SkillRuntimeContext 中 → 供 Plan 阶段解析工具参数」

最终箭头指向输出框：「SkillWorkflowResult」
内部分两行：
- selectedSkills: List<String>（最终选定的 Skill 名称）
- selectedSkillContexts: List<SkillRuntimeContext>（Skill 上下文对象）

底部标注：
- Skill 来源：「项目内建 Skills: src/main/resources/skills」「用户 Skills: src/main/resources/user-skills」
- 工作流配置：「SkillWorkflowConfig.skillWorkflowGraph()」→ CompiledGraph
- 调用者：「SkillWorkflowServiceImpl.preprocess() → AgentServiceImpl.call() 步骤 4」

图片比例 16:9，分辨率 2560×1440，清晰简洁的横向流程图，所有文字中英文对照。
```

**预期输出**：一张横向 6 节点流程图，展示 Skill 从绑定到上下文输出的完整过程。

---

## 九、Spring Boot 项目模块依赖图

```
生成一张 Maven/Spring Boot 项目的模块依赖和组件关系图。白色背景，使用深蓝色系（#1e40af、#3b82f6、#93c5fd）作为渐变主色调。

中心放置大圆角矩形「cloud-cold-agent — Spring Boot 3.5.13 Application」
内部标注「@SpringBootApplication + @EnableScheduling + @EnableAspectJAutoProxy + @MapperScan」

从中心向外辐射 6 个模块分组：

分组 1「Web / API 层」（上方）：
- 11 个 Controller（AgentController 等）
- @AuthCheck 鉴权注解
- WebConfig (ToStringSerializer Long→String)
- CorsConfig、RedisSessionConfig
- GlobalResponseWrapper

分组 2「Agent 执行层」（右上方）：
- BaseAgent（抽象基类）
- SimpleReactAgent（fast 模式：ReAct 循环 + SSE）
- PlanExecuteAgent（thinking/expert：Plan-Execute-Critique-Summarize）
- 共享组件：chatModel、tools(commonTools)、advisors、chatMemory

分组 3「工作流与工具层」（右方）：
- SkillWorkflowConfig + 6 个 Node
- SkillWorkflowService + StructuredOutputAgentExecutor
- SkillRegistry 三层架构（接口 → FileSystem → Caching）
- 8 个 Tool（commonTools: 2 个, 全部: 8 个）
- GraalVM Python Script Runtime

分组 4「领域服务层」（右下方）：
- 知识库：KnowledgePreprocessService、KnowledgeDocumentIngestionService、DocumentReaderStrategy(PdfMultimodalProcessor)
- 长期记忆：UserLongTermMemoryPreprocessService、UserLongTermMemoryService、UserLongTermMemoryStore
- HITL：HitlCheckpointService、HitlExecutionService、HitlResumeService
- 聊天：MysqlChatMemoryRepository

分组 5「基础设施层」（下方）：
- 数据源：MySQL + MyBatis-Flex (14 Mappers)
- 搜索引擎：Elasticsearch (rag_docs, rag_docs_vector, user_long_term_memory_docs, user_long_term_memory_vector)
- 缓存：Redis (Session + 宠物状态)
- 对象存储：MinIO (PDF + 图片)
- AI API：DashScope (qwen-turbo, text-embedding-v4, qwen3-vl-plus)
- 并发：Virtual Thread Executor、Agent Tool Task Executor

分组 6「横切关注点」（左侧竖排）：
- @AuthCheck → AuthInterceptor
- @DistributeLock → DistributeLockAspect
- RateLimiter → SlidingWindowRateLimiter
- GlobalExceptionHandler

各组之间用箭头线连接，关键依赖标上文字说明。

底部列出技术版本：Java 21, Spring Boot 3.5.13, Spring AI, Spring AI Alibaba Agent Framework, MyBatis-Flex, Elasticsearch 8.x, Redis 6/7, MinIO

图片比例 16:9，分辨率 2560×1440。
```

**预期输出**：一张中心辐射式的完整项目模块依赖图。

---

## 十、数据模型 ER 关系图

```
生成一张核心数据库表的 ER 关系图，展示项目中的主要实体及其关联关系。白色背景，经典数据库 ER 图风格，表使用浅灰底深蓝边框矩形。

核心表及其关键字段：

表 1「user」(用户表)：
字段：id (PK, bigint)、userAccount、userPassword、userRole (USER/ADMIN)、createTime、updateTime
与 user_conversation_relation 是一对多关系

表 2「chat_conversation」(会话表)：
字段：id (PK, bigint)、conversationId (UK, varchar)、title、selectedSkillList、selectedKnowledgeId、createTime、updateTime
关联：conversation_skill_relation（会话-Skill 多对多）、conversation_knowledge_relation（会话-知识库 多对多）

表 3「chat_memory_history」(聊天记忆表)：
字段：id (PK, bigint)、conversationId (FK)、messageType (USER/ASSISTANT)、content、createTime
关联表：chat_memory_history_image_relation（助手消息-知识库图片关联）

表 4「knowledge」(知识库表)：
字段：id (PK, bigint)、knowledgeId (UK)、name、description、userId (FK → user)、createTime

表 5「document」(文档表)：
字段：id (PK, bigint)、documentId (UK)、knowledgeId (FK → knowledge)、fileName、status (PENDING/INDEXING/INDEXED/FAILED)、minioObjectKey、createTime
关联表：knowledge_document_image（文档-图片关联）

表 6「hitl_checkpoint」(HITL 检查点表)：
字段：id (PK, bigint)、interruptId (UK)、conversationId、toolName、status (PENDING/RESOLVED/CONSUMED/CANCELLED)、argumentsJson、createTime

表 7「user_long_term_memory」(长期记忆表)：
字段：id (PK, bigint)、userId (FK → user)、memoryId (UK)、content、memoryType、keywords、extractionRound、confidence、createTime
关联表：user_long_term_memory_source_relation（记忆-来源消息关联）
关联表：user_long_term_memory_conversation_state（记忆-会话状态，记录 lastProcessedMessageId 和 totalRounds）

表之间用标准 ER 连线：
- 一对多：用单线-三叉线
- 多对多：中间加关联表
- 外键：标注 FK

底部列出 ES 索引（非关系型）：
- rag_docs (关键词索引)
- rag_docs_vector (向量索引, 1536 维, cosine)
- user_long_term_memory_docs (记忆关键词索引)
- user_long_term_memory_vector (记忆向量索引, 1536 维)

图片比例 16:9，分辨率 2560×1440。
```

**预期输出**：一张标准 ER 图，展示 7 张核心业务表 + 4 个 ES 索引的结构和关系。

---

## 提示词编写原则

1. **中英混杂**：技术术语（如 Agent、SSE、HITL、PlanTask、Checkpoint）保留英文原名，AI 模型理解更准确
2. **结构化描述**：每个提示词遵循「布局→节点→连线→样式→尺寸」顺序
3. **色彩语义化**：蓝=核心流程、绿=成功/输出、橙=中断/审批、紫=高级功能、红=异常
4. **基于真实代码**：所有类名、方法名、配置项、数据表均来自项目源码，确保图表与代码一致
5. **分图独立**：每个提示词可独立使用，不依赖前文上下文
