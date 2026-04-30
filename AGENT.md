# AGENT.md

本文件面向 Claude Code、Codex、Cursor、Windsurf 等 AI 编程工具，目标是让新工具在进入 `cloud-cold-agent` 仓库后，能快速理解项目用途、代码结构、关键链路和修改注意事项。

## 1. 项目定位

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端项目，一个基于 `Spring Boot + Spring AI + MyBatis Flex + Redis Session + Elasticsearch + MinIO` 的 AI Agent 服务。

它主要提供四类能力：

- 会话式 AI Agent 对话
- Skill 工作流预处理与脚本执行
- HITL（Human-in-the-loop）人工审批与恢复执行
- 知识库文档上传、入库、索引与检索

## 2. 快速事实

- 语言与运行时：Java 21
- 构建工具：Maven Wrapper（`./mvnw`）
- Spring Boot：3.5.x
- 应用入口：`src/main/java/com/shenchen/cloudcoldagent/CloudColdAgentApplication.java`
- 根包名：`com.shenchen.cloudcoldagent`
- 默认端口：`8081`
- 接口前缀：`/api`
- 默认激活 Profile：`local`

## 3. 启动依赖

本项目默认依赖以下基础设施：

- MySQL：业务表、聊天记忆、会话、HITL、知识库元数据
- Redis：Spring Session 登录态
- Elasticsearch：关键词索引、向量索引
- MinIO：原始文档文件存储
- OpenAI 兼容模型服务：聊天模型、embedding、PDF 多模态处理

## 4. 配置规则

当前配置策略非常重要，后续修改配置请遵守：

- `src/main/resources/application.yml` 必须包含一套完整、可解释的基础配置
- `src/main/resources/application-local.yml` 是可选的本地覆盖文件，可能不存在，也不会提交到仓库
- 如果存在 `application-local.yml`，则在 `spring.profiles.active=local` 时覆盖 `application.yml` 的同名配置
- 如果不存在 `application-local.yml`，则项目直接使用 `application.yml`
- 优先使用环境变量覆盖敏感配置，不要把真实密钥硬编码回仓库
- 当前配置属性类统一位于 `src/main/java/com/shenchen/cloudcoldagent/config/properties`

当前最关键的配置组：

- `spring.datasource.*`
- `spring.data.redis.*`
- `spring.elasticsearch.*`
- `spring.ai.openai.*`
- `spring.ai.alibaba.toolcalling.tavilysearch.*`
- `minio.*`
- `cloudcold.hitl.*`
- `cloudcold.search.mock.*`
- `cloudcold.pdf.multimodal.*`

## 5. 项目开发规范

后续在本项目中写代码、改代码、生成代码时，默认必须遵循以下规范。

### 5.1 依赖注入规范

- 必须使用显式构造函数进行依赖注入
- 不允许使用 Lombok 的 `@RequiredArgsConstructor`、`@AllArgsConstructor` 等注解生成注入构造函数
- 不允许使用字段注入，例如 `@Autowired private XxxService xxxService;`
- 不允许使用 setter 注入作为默认方案
- Spring 组件中的依赖字段应优先声明为 `private final`

推荐写法：

```java
@Service
public class DemoService {

    private final UserService userService;

    public DemoService(UserService userService) {
        this.userService = userService;
    }
}
```

### 5.2 MySQL 访问规范

- 任何访问 MySQL 的行为，都必须使用项目当前依赖的 `MyBatis-Flex`
- 不允许引入或使用其它数据库访问方案替代当前主链路，例如：
  - 原生 JDBC
  - `JdbcTemplate`
  - MyBatis 原生 XML + 手写风格替代当前约定
  - MyBatis-Plus
  - JPA / Hibernate
  - Spring Data JDBC
- 新增数据库查询、更新、分页、条件拼装时，应沿用现有模式：
  - `Mapper`
  - `ServiceImpl`
  - `QueryWrapper`
  - `Page`

如果需要新增数据库能力，优先参考现有类：

- `ChatConversationServiceImpl`
- `UserServiceImpl`
- `KnowledgeServiceImpl`
- `DocumentServiceImpl`

### 5.3 分层规范

- Controller 负责接口参数接收、基础校验、登录用户获取和响应封装
- 业务编排应尽量放在 Service / ServiceImpl 中
- 不要把复杂业务逻辑直接堆到 Controller
- Agent 相关复杂流程应放在 `AgentServiceImpl`、`SimpleReactAgent`、`PlanExecuteAgent`、`workflow/skill/*` 中
- DTO、VO、Entity、Record 要放在现有 `model` 分层下，不要随意混放

### 5.4 接口与响应规范

- 普通 JSON 接口统一返回 `BaseResponse<T>`
- 成功返回优先使用 `ResultUtils.success(...)`
- 参数校验失败、权限失败、资源不存在等情况，优先沿用项目已有异常体系：
  - `BusinessException`
  - `ErrorCode`
  - `ThrowUtils`
- 不要随意新增一套平行的错误码和响应结构

### 5.5 SSE 与 Agent 事件规范

- `/agent/call` 和 `/agent/resume` 必须继续使用当前 SSE 机制
- SSE 事件名保持为 `agent`
- Agent 事件类型、字段命名、`conversationId` / `interruptId` 语义要尽量保持兼容
- 如果修改 `AgentStreamEvent` 或 `AgentStreamEventFactory`，必须同步检查前端消费逻辑

### 5.6 配置规范

- 基础配置写在 `application.yml`
- `application-local.yml` 视为本地覆盖文件，不保证存在，也不应作为仓库中唯一配置来源
- 新增配置项时，必须保证：
  - `application.yml` 中有完整结构
  - 支持环境变量覆盖
  - 不把真实密钥写入仓库
- 新增配置属性类时，优先使用现有 `@ConfigurationProperties` 风格，并放在 `config.properties` 包下

### 5.7 持久化与状态一致性规范

- 涉及会话、知识库、文档、HITL 的操作，必须校验用户归属或访问权限
- 删除行为要考虑现有的逻辑删除、级联删除、关联清理策略
- 修改文档入库链路时，要同时考虑：
  - MySQL 元数据状态
  - MinIO 原文件
  - Elasticsearch 索引
- 修改聊天记忆链路时，要遵守当前 `MysqlChatMemoryRepository` 的持久化方式，不要绕开它另建一套记忆存储机制

### 5.8 工具与 Skill 规范

- 新增 Agent 工具时，优先沿用 `BaseTool` 体系
- 工具需要进入通用工具池时，应兼容 `ToolRegistrationConfig`
- Skill 相关能力优先接入现有 `workflow/skill/*` 与 `SkillService`
- 不要绕开现有 Skill 工作流，直接在 Controller 中拼接零散逻辑

### 5.9 前后端协同规范

- 任何会影响前端接口契约的改动，都必须明确检查 `cloud-cold-frontend`
- 特别是以下改动，必须默认视为前后端联动改动：
  - SSE 事件结构
  - Agent 模式值
  - 会话字段
  - HITL 反馈结构
  - 知识库 / 文档接口返回字段

### 5.10 禁止事项

- 禁止重新引入注解式字段注入
- 禁止绕开 `MyBatis-Flex` 直接使用其它 ORM / JDBC 方案访问 MySQL
- 禁止提交真实密钥、真实密码、个人机器路径等敏感信息
- 禁止在未评估前端影响的情况下随意修改 SSE 事件协议
- 禁止新增与项目现有风格明显冲突的基础设施层方案

## 6. 代码地图

最关键的目录与职责如下：

- `controller/`
  负责 REST 接口与 SSE 出口
- `service/`
  业务接口定义
- `service/impl/`
  核心业务实现，绝大多数实际逻辑都在这里
- `agent/`
  智能体实现，核心是 `SimpleReactAgent` 与 `PlanExecuteAgent`
- `workflow/skill/`
  Skill 工作流图、节点、状态对象
- `tools/`
  Agent 可调用的工具
- `memory/`
  聊天记忆持久化实现
- `config/`
  Spring 配置、工具注册、基础 Bean 装配
- `config/properties/`
  `@ConfigurationProperties` 配置属性对象
- `model/`
  DTO、VO、Entity、Record、Enum
- `mapper/`
  MyBatis Flex Mapper

## 7. 最重要的文件

如果要理解主链路，优先看这些文件：

- `controller/AgentController.java`
  Agent 的 `/agent/call` 和 `/agent/resume` SSE 接口入口
- `service/impl/AgentServiceImpl.java`
  Agent 总编排层，决定模式路由、Skill 预处理、HITL 恢复入口
- `agent/SimpleReactAgent.java`
  `fast` 模式实现
- `agent/PlanExecuteAgent.java`
  `thinking` / `expert` 模式实现，包含规划、执行、批判、总结、HITL 中断恢复
- `workflow/skill/service/impl/SkillWorkflowServiceImpl.java`
  Skill 工作流执行入口
- `workflow/skill/config/SkillWorkflowConfig.java`
  Skill 图编排顺序
- `common/AgentStreamEventFactory.java`
  SSE 事件构造统一入口
- `memory/MysqlChatMemoryRepository.java`
  聊天记忆的 MySQL 持久化实现
- `service/impl/KnowledgeDocumentIngestionServiceImpl.java`
  文档上传、对象存储、入库与索引主链路
- `service/impl/HitlResumeServiceImpl.java`
  人工审批后恢复执行的核心逻辑
- `config/EsConfig.java`
  Elasticsearch 客户端配置入口
- `config/ToolRegistrationConfig.java`
  Agent 工具集合装配点
- `config/properties/*`
  配置绑定对象，例如 `HitlProperties`、`MinioProperties`、`EsProperties`

## 8. 核心业务链路

### 8.1 `/agent/call`

主链路如下：

1. `AgentController.call()` 接收请求
2. 校验登录态
3. 若未传 `conversationId`，Controller 会先自动创建会话
4. 创建 `SseEmitter`
5. 调用 `AgentServiceImpl.call()`
6. `AgentServiceImpl` 做以下事情：
   - 标准化会话 ID
   - `touchConversation()`
   - 首条消息自动生成会话标题
   - 调用 `SkillWorkflowService.preprocess()`
   - 如果 Skill 缺少必填参数，直接短路返回 `final_answer`
   - 根据模式路由到具体 Agent
7. Agent 产出的 `Flux<AgentStreamEvent>` 被转发成 SSE 返回前端

### 8.2 模式路由

后端支持三种模式：

- `fast` -> `SimpleReactAgent`
- `thinking` -> `PlanExecuteAgent`
- `expert` -> `PlanExecuteAgent`

注意：

- 当前后端里 `expert` 与 `thinking` 共用同一条实现链路
- 当前前端默认只暴露 `fast` 和 `thinking`

### 8.3 Skill 预处理链路

真正进入 Agent 推理前，先走一轮 Skill 工作流。图结构定义在 `SkillWorkflowConfig`，当前顺序为：

1. `loadBoundSkills`
2. `loadConversationHistory`
3. `recognizeBoundSkills`
4. `discoverCandidateSkills`
5. `loadSkillContents`
6. `buildSkillExecutionPlans`
7. `buildEnhancedQuestion`

产出结果包含：

- `selectedSkills`
- `executionPlans`
- `enhancedQuestion`

这些结果会影响后续 Agent 的问题输入与首选计划。

### 8.4 `/agent/resume`

HITL 恢复链路如下：

1. 前端先调用 `/hitl/checkpoint/resolve` 提交审批结果
2. 前端再调用 `/agent/resume`
3. `AgentController.resume()` 建立 SSE
4. `AgentServiceImpl.resume()` 根据 `interruptId` 找到 checkpoint
5. 当前仅 `PlanExecuteAgent` 支持 resume
6. `HitlResumeServiceImpl` 会把用户审批结果重新写回消息上下文，再继续工具执行与模型调用

### 8.5 文档入库链路

`/document/upload` 的典型处理顺序：

1. 校验用户与知识库归属
2. 文件先上传到 MinIO
3. 文档元数据写入 MySQL
4. 状态从 `PENDING` -> `INDEXING`
5. 临时落盘并调用 `knowledgeService.indexDocument(...)`
6. 文本块写入 Elasticsearch
7. 成功则更新为 `INDEXED`
8. 失败则回滚索引并更新为 `FAILED`

## 9. SSE 协议约定

Agent SSE 的事件名固定为：

- `agent`

事件类型由 `AgentStreamEvent.type` 决定，当前主要包括：

- `thinking_step`
- `assistant_delta`
- `final_answer`
- `hitl_interrupt`
- `error`

前端会强依赖这些名字。如果修改事件类型、字段结构或 `conversationId/interruptId` 的行为，必须同步前端仓库：

- `cloud-cold-frontend/src/api/agent.ts`
- `cloud-cold-frontend/src/types/agent.ts`
- `cloud-cold-frontend/src/views/HomeView.vue`

## 10. 数据存储分工

理解“数据到底落在哪里”对定位问题非常重要：

- MySQL
  - 用户
  - 会话
  - 聊天记忆
  - 用户与会话关系
  - HITL checkpoint
  - 知识库元数据
  - 文档元数据
- Redis
  - 仅存储 Session 登录态
- Elasticsearch
  - 文档关键词索引
  - 文档向量索引
- MinIO
  - 文档原始文件

特别注意：

- 聊天记忆不是存在 Redis 里，而是通过 `MysqlChatMemoryRepository` 存在 MySQL

## 11. 认证与权限

鉴权模型如下：

- 登录态基于 `Http Session + Redis`
- 大多数业务接口使用 `@AuthCheck`
- 实际校验逻辑在 `aop/AuthInterceptor.java`
- Controller 中常通过 `userService.getLoginUser(request)` 获取当前登录用户

如果修改接口权限，请同时检查：

- 是否有 `@AuthCheck`
- 是否在 Service 层校验了资源归属
- 会话、知识库、文档是否做了 userId 级别隔离

## 12. 工具系统

Agent 可用工具来自 `BaseTool` 的 Spring Bean 集合。

关键规则：

- `ToolRegistrationConfig` 会自动收集 `BaseTool` 并构建 `allTools`
- `ReadSkillTool`、`ReadSkillResourceTool`、`ListSkillResourcesTool` 会被排除在通用工具集合外
- 如果新增工具，通常做法是新增一个 `BaseTool` 子类并让 Spring 托管
- 如果某个工具需要 HITL 审批，把工具名加入 `cloudcold.hitl.intercept-tool-names`

当前要特别知道的一点：

- `SearchTool` 默认可以跑在 mock 模式下
- `cloudcold.search.mock.enabled=true` 时，不会真的联网，而是返回固定调试数据

## 13. 推荐的排查顺序

当你接到一个新任务时，建议按下面顺序建立上下文：

1. 先看对应 Controller，确认入口接口和请求参数
2. 再看对应 ServiceImpl，确认真实业务编排
3. 如果是 Agent 相关，再看 `AgentServiceImpl -> Agent 实现类`
4. 如果是 Skill/HITL，再继续深入 `workflow/skill/*` 或 `hitl/*`
5. 如果接口要给前端用，再检查 `cloud-cold-frontend` 是否需要同步

## 14. 修改原则
- 保持会话归属校验不被绕过
- 保持 SSE 事件类型和字段兼容，除非同步修改前端
- 保持配置可在没有 `application-local.yml` 的情况下被完整解释
- 不要提交真实密钥、真实本地密码或个人环境配置
- 大改 `PlanExecuteAgent`、HITL 或索引链路前，先缩小改动范围并验证关键路径
