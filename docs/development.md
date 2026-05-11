# 后端开发

本文说明 `cloud-cold-agent` 的本地开发、配置、启动、验证和排查方式。

## 环境要求

- JDK 21
- Maven 3.9+，或仓库内 `./mvnw`
- MySQL 8.x
- Redis 6.x / 7.x
- Elasticsearch 8.x
- MinIO
- OpenAI 兼容聊天模型
- OpenAI 兼容 Embedding 模型
- PDF 多模态模型

## 默认配置

当前 `src/main/resources/application.yml` 的关键运行值：

| 配置项 | 当前值 |
| --- | --- |
| `server.port` | `8081` |
| `server.servlet.context-path` | `/api` |
| `spring.profiles.active` | `local` |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/cloud_cold` |
| `spring.datasource.username` | `root` |
| `spring.datasource.password` | `your-mysql-password-here` |
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

注意：`@ConfigurationProperties` 类中的默认值不一定等于当前运行值。描述当前行为时以 `application.yml` 为准。

## 本地覆盖配置

建议使用未提交的 `src/main/resources/application-local.yml` 覆盖账号、密码和密钥：

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

cloudcold:
  pdf:
    multimodal:
      api-key: your_multimodal_api_key
      base-url: https://your-openai-compatible-endpoint
      model: qwen3-vl-plus
```

如果要关闭 mock 搜索并启用真实联网搜索，还需要：

```yaml
spring:
  ai:
    alibaba:
      toolcalling:
        tavilysearch:
          enabled: true
          api-key: your_tavily_key

cloudcold:
  search:
    mock:
      enabled: false
```

## 数据库初始化

初始化脚本：

```text
src/main/java/com/shenchen/cloudcoldagent/database/init.sql
```

核心表包括：

- `user`
- `chat_memory_history`
- `chat_memory_history_image_relation`
- `chat_conversation`
- `user_conversation_relation`
- `conversation_skill_relation`
- `conversation_knowledge_relation`
- `hitl_checkpoint`
- `knowledge`
- `knowledge_document`
- `knowledge_document_image`
- `user_long_term_memory`
- `user_long_term_memory_source_relation`
- `user_long_term_memory_conversation_state`

脚本会写入测试账号：

- `admin / 12345678`
- `user / 12345678`
- `test / 12345678`

密码加密方式是 `MD5(password + "salt")`。

## 启动

先确保 MySQL、Redis、Elasticsearch、MinIO 和模型服务可用，然后运行：

```bash
./mvnw spring-boot:run
```

默认地址：

- 服务地址：[http://localhost:8081/api](http://localhost:8081/api)
- Knife4j：[http://localhost:8081/api/doc.html](http://localhost:8081/api/doc.html)

也可以打包后启动：

```bash
./mvnw clean package
java -jar target/cloud-cold-agent-0.0.1-SNAPSHOT.jar
```

当前 `SkillConfig` 从文件系统路径 `src/main/resources/skills` 加载本地 Skill。使用裸 JAR 启动时，工作目录仍然要能访问该路径，否则 SkillRegistry 初始化会失败。

## 验证

编译检查：

```bash
./mvnw -q -DskipTests compile
```

运行测试：

```bash
./mvnw test
```

## 联调前端

前端仓库是 `cloud-cold-frontend`。

- 普通 JSON 请求固定带 `credentials: 'include'`。
- 本地前端常见地址 `http://localhost:5173` 会直接请求 `http://localhost:8081/api`。
- Agent 流式接口使用 `text/event-stream`，前端通过 `fetch + getReader` 手动解析。
- Cookie / Redis Session 是登录态联调的第一排查点。

## 常见排查

- 登录后仍提示未登录：检查 Cookie 是否携带，以及 Redis Session 是否可用。
- SSE 没有内容：检查 `/agent/call` 响应头是否为 `text/event-stream`。
- 搜索结果像假数据：当前默认 `cloudcold.search.mock.enabled=true`。
- 联网搜索不生效：确认关闭 mock，并配置 Tavily。
- 文档上传很慢：当前上传接口同步完成 PDF 解析、抽图、索引，不是异步任务队列。
- 文档上传失败：检查 MinIO、Elasticsearch、Embedding 模型和 PDF 多模态模型。
- 知识库图片不回显：检查父块 `metadata.imageIds` 是否包含有效图片 ID，以及 MinIO 预签名 URL 是否生成成功。
- 长期记忆没有刷新：确认是否达到 `trigger-rounds=5`，再检查整点调度或手动 `/userMemory/rebuild`。
- 前端 ID 比较异常：优先确认后端返回的 `Long` 是否为字符串。
