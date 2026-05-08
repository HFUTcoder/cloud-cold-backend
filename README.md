# cloud-cold-agent

`cloud-cold-agent` 是 `cloud-cold-frontend` 的后端项目，提供基于 `Spring Boot 3 + Spring AI + MyBatis-Flex + Redis Session + Elasticsearch + MinIO` 的 AI Agent 服务。

## 当前能力

- 会话式 Agent 对话，支持 `fast`、`thinking`、`expert` 三种后端模式
- Skill 工作流预处理与 Python 脚本执行
- HITL 人工审批与恢复执行
- 知识库 PDF 入库、混合检索与图片回显
- 用户长期记忆 / 宠物记忆

## 功能演示

- 思考模式 绑定 skill 执行个税脚本

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/1.mp4" controls width="720"></video>

- 思考模式 不绑定 skill 执行个税脚本

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/2.mp4" controls width="720"></video>

- 快速模式 对话问答 执行个税脚本

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/3.mp4" controls width="720"></video>

- 知识库创建

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/4.mp4" controls width="720"></video>

- 知识库检索 支持图像

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/5.mp4" controls width="720"></video>

- 个人宠物 长期记忆

  <video src="https://raw.githubusercontent.com/HFUTcoder/cloud-cold-backend/main/docs/videos/6.mp4" controls width="720"></video>

## 快速事实

- 默认服务地址：`http://localhost:8081/api`
- 默认 Profile：`local`
- 登录态：`HttpSession + Redis Session`，前端联调必须携带 Cookie
- API 文档：`http://localhost:8081/api/doc.html`
- 数据库初始化脚本：`src/main/java/com/shenchen/cloudcoldagent/sql/init.sql`
- Agent 主工具池当前只注册 `search` 和 `execute_skill_script`
- 文档上传当前只支持 PDF，并且 `POST /document/upload` 是同步入库
- 后端会把 JSON 里的 `Long` / `long` 序列化成字符串

## 本地启动

环境要求：

- JDK 21
- Maven 3.9+，或直接使用仓库里的 `./mvnw`
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- OpenAI 兼容的聊天模型、Embedding 模型和 PDF 多模态模型

初始化数据库后，按需在未提交的 `src/main/resources/application-local.yml` 中覆盖账号、密码和密钥。然后启动：

```bash
./mvnw spring-boot:run
```

也可以编译检查：

```bash
./mvnw -q -DskipTests compile
```

更多环境变量、配置项和排查方式见 [docs/development.md](docs/development.md)。

## 文档

- [AGENTS.md](AGENTS.md)：AI 工具入口，只保留项目地图和硬性规则。
- [docs/architecture.md](docs/architecture.md)：后端分层架构、核心链路、模块说明。
- [docs/development.md](docs/development.md)：本地开发、配置、启动、验证、常见问题。
- [docs/design-docs/ref-backend-architecture.md](docs/design-docs/ref-backend-architecture.md)：参考项目架构说明。
- [docs/design-docs/backend-patterns.md](docs/design-docs/backend-patterns.md)：后端组件使用模式和编码约定。

## 与前端联动

前端仓库是 `cloud-cold-frontend`。修改以下契约时通常需要同步前端：

- SSE 事件类型和字段
- `conversationId` / `interruptId` 语义
- Agent 模式值
- 会话绑定 Skill / 知识库字段
- 聊天历史里的 `retrievedImages`
- HITL payload 结构
- `Long -> string` 的序列化策略
