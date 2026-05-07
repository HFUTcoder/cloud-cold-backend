# 参考项目架构说明

本文记录 `cloud-cold-agent` 当前真实架构，供新功能设计和 AI 工具上下文引用。

## 总体设计

后端不是泛管理后台，而是围绕一个会话式 AI Agent 服务展开。主入口是 `/agent/call`，它不是直接把用户问题丢给模型，而是在进入 Agent 前完成三类增强：

- Skill 工作流预处理
- 会话绑定知识库的预检索
- 用户长期记忆召回

随后才根据模式路由到 `SimpleReactAgent` 或 `PlanExecuteAgent`。

## 关键边界

- Controller 负责协议接入和响应输出，不承担复杂业务。
- `AgentServiceImpl` 是 Agent 主编排，不应被绕开。
- Skill 预处理统一走 `workflow/skill/*`。
- 知识库问答优先看 `KnowledgePreprocessServiceImpl`，不是优先看 RAG tools。
- 文档入库跨 MySQL、MinIO、Elasticsearch 和模型服务，任何删除或失败恢复都必须考虑一致性。
- 长期记忆前置召回和后置整理都已经接入主链路。

## 当前 Agent 模式

- `fast`：`SimpleReactAgent`
- `thinking`：`PlanExecuteAgent`
- `expert`：`PlanExecuteAgent`

前端当前只暴露 `fast` 和 `thinking`，后端仍保留 `expert`。

## 当前工具池

`ToolRegistrationConfig` 提供两个 Bean：

- `allTools`：所有 `BaseTool` 的集合。
- `commonTools`：Agent 主链路当前实际注入的工具池。

`commonTools` 当前只包含：

- `search`
- `execute_skill_script`

因此新增 Tool 时，仅注册 Spring Bean 不代表 Agent 主链路会使用它。

## 数据与外部系统

- MySQL：用户、会话、历史、HITL、知识库、文档、长期记忆元数据。
- Redis：Session 与长期记忆宠物元数据。
- Elasticsearch：知识库关键词索引和长期记忆关键词索引。
- Elasticsearch Vector Store：知识库向量索引和长期记忆向量索引。
- MinIO：原始 PDF 和 PDF 图片对象。
- OpenAI 兼容模型：聊天、Embedding、PDF 图片描述。

## 变更影响面

以下变更通常跨前后端：

- SSE 协议
- HITL payload
- Agent 模式值
- Skill / 知识库绑定字段
- 文档状态值
- 聊天历史图片字段
- 长期记忆和宠物状态字段
- `Long -> string` 序列化策略
