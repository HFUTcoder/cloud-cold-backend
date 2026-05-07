# 后端组件使用模式

本文记录 `cloud-cold-agent` 中应继续遵守的组件模式和编码约定。

## Controller 模式

- 继续保持 Controller 薄层：参数接收、登录态获取、调用 Service、包装响应。
- 主业务接口优先返回统一 `BaseResponse<T>`。
- SSE 接口由 `AgentController` 维护 `SseEmitter` 输出，不要在页面特定逻辑里新增旁路流式协议。
- 当前 `/files/*` 和 `/es/*` 更偏调试 / 辅助接口，不属于前端主链路。

## Service 模式

- 业务编排、资源归属校验、状态流转放在 Service。
- 跨 MySQL、ES、MinIO 的操作要考虑失败补偿和清理。
- 删除逻辑不能只删数据库，也要同步处理索引、对象存储和关联表。

## 依赖注入与代码风格

- Spring 组件继续使用显式构造函数注入。
- 不重新引入字段注入。
- 日志统一使用 `Slf4j`。
- DTO / VO / Entity / Record 按现有目录分层放置。
- MySQL 主链路继续使用 MyBatis-Flex。

## Tool 模式

- 新 Tool 优先继承 `BaseTool`。
- 如果 Tool 要进入 Agent 主链路，需要加入 `commonTools`。
- 如果 Tool 需要人工审批，需要把工具名加入 `cloudcold.hitl.intercept-tool-names`。
- 不要因为存在 `allTools` 就假设 Agent 会自动使用所有工具。

## Skill 模式

- Skill 预处理统一走 `workflow/skill/*`。
- 不要在 Controller 或 Agent 中手写另一套 Skill 识别逻辑。
- `execute_skill_script` 是当前默认 HITL 拦截点。
- 本地 Skill 目录当前是文件系统路径 `src/main/resources/skills`。

## 知识库 / 文档模式

- 当前只支持 PDF。
- 扩展文档类型时需要新增 `DocumentReaderStrategy`，并同步前端上传限制和文档。
- PDF 入库同时生成正文 chunk 和图片描述 chunk。
- 修改入库链路时必须同步考虑：
  - `knowledge_document`
  - `knowledge_document_image`
  - MinIO 原文件
  - MinIO 图片对象
  - `rag_docs`
  - `rag_docs_vector`

## 长期记忆模式

- 对话前召回入口是 `UserLongTermMemoryPreprocessServiceImpl`。
- 对话后累计触发入口是 `MysqlChatMemoryRepository`。
- 整点兜底任务是 `UserLongTermMemoryScheduler`。
- 不要在 Controller 层补另一套长期记忆触发逻辑。

## 前后端契约模式

- `Long` / `long` 当前会以字符串形式返回 JSON。
- SSE 事件名当前固定为 `agent`。
- 聊天历史不会持久化实时思考过程。
- 知识库命中的图片通过 `knowledge_retrieval` 实时推送，并在助手消息落库后回绑历史。
