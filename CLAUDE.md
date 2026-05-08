# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

AI Agent backend for `cloud-cold-frontend`. Java 21, Spring Boot 3.5.13, Spring AI Alibaba Agent Framework, MyBatis-Flex, MySQL, Redis Session, Elasticsearch, MinIO, GraalVM Polyglot Python.

Full details: read `AGENTS.md` (auto-loaded) and `docs/architecture.md`.

## Quick commands

| Purpose | Command |
|---------|---------|
| Compile check | `./mvnw -q -DskipTests compile` |
| Run tests | `./mvnw test` |
| Start server | `./mvnw spring-boot:run` |
| Package | `./mvnw clean package` |

Server runs on `http://localhost:8081/api`. API docs at `http://localhost:8081/api/doc.html`.

## Critical conventions (read AGENTS.md §5 for full details)

- **Agent tool pool**: `commonTools` (in `ToolRegistrationConfig`), not `allTools`. Currently only `SearchTool` and `ExecuteSkillScriptTool`.
- **Knowledge base retrieval** happens in service-layer preprocessing (`KnowledgePreprocessServiceImpl.preprocess()`), NOT via `tools/rag/*` at runtime.
- **Document upload** (`POST /document/upload`) is synchronous — returns after INDEXED or FAILED. PDF-only currently.
- **Long / long serialized as JSON strings** via `WebConfig` `ToStringSerializer`. Changing this is a frontend contract break.
- **HITL** interrupt/resume flow: `PlanExecuteAgent` → checkpoint → `POST /agent/resume`.
- **MyBatis-Flex only** for data access — do not introduce JPA or MyBatis-Plus.
- **Complex logic stays in Service/Workflow/Agent layers**, not Controllers.
- **Chat history** persists only user messages, final assistant answers, and bound knowledge images — not real-time thinking.

## Configuration

All config in `src/main/resources/application.yml`. Secrets marked `TODO:` (DashScope API Key, MySQL password, MinIO credentials, optional Tavily Key). Local overrides in `application-local.yml` (gitignored).

## Long-term memory / pet memory

Triggered after N conversation rounds (config: `cloudcold.long-term-memory.trigger-rounds`). Recall happens before Agent entry via `UserLongTermMemoryPreprocessService`. MySQL + ES dual-write. Cleanup must be consistent across MySQL and ES — read AGENTS.md §5 for the exact sequence.

## Code layout (see AGENTS.md §3 for full tree)

```
src/main/java/com/shenchen/cloudcoldagent/
├── agent/        — SimpleReactAgent / PlanExecuteAgent
├── aop/          — Auth, distributed lock aspects
├── config/       — Web, Tool, ES, MinIO, Session, RateLimiter config
├── controller/   — REST + SSE endpoints
├── document/     — PDF reading, cleaning, chunking, indexing
├── hitl/         — HITL advisor and state objects
├── memory/       — MySQL chat memory persistence
├── model/        — Entity, DTO, VO
├── prompts/      — Long-term memory extraction & knowledge base prompts
├── service/      — Business logic (usermemory/, impl/)
├── tools/        — Agent tools
├── workflow/     — Skill workflow graph, nodes, state
└── job/          — UserLongTermMemoryScheduler (hourly)
```
