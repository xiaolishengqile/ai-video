# Pipeline 恢复旧会话修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复已取消任务点击“继续”只回放旧 CANCELLED 流、不创建新执行 attempt 的问题。

**Architecture:** 在实体映射层保证 `activeConversationId=null` 会被 MyBatis 写入数据库；在 Pipeline 恢复入口优先依据服务端恢复策略处理终态任务，兼容历史遗留的终态加旧 active conversation 脏数据。保持现有 API 和前端交互不变。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Reactor、JUnit 5、Mockito。

## Global Constraints

- 直接在用户指定的 `main` 分支修复。
- 不修改现有数据库数据，不新增数据库结构。
- 使用最小变更，不调整 Pipeline 并发或检查点策略。

---

### Task 1: 覆盖并修复终态恢复分支

**Files:**
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntimeTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntime.java`

**Interfaces:**
- Consumes: `PipelineRecoveryPolicy.decide(...)`。
- Produces: `AgentScopePipelineRuntime.resume(String, Long)` 对可恢复终态调用 `PipelineRuntimeService.startManualResume(...)`。

- [x] 增加失败测试：`CANCELLED` 且残留 `activeConversationId` 时必须启动 MANUAL attempt，不能调用 `reconnect`。
- [x] 运行 `AgentScopePipelineRuntimeTests`，确认当前实现因返回旧流而失败。
- [x] 修改 `resume`：`recoveryAction == RESUME` 时启动新 attempt；只有不可恢复但仍活动的任务才重连。
- [x] 重跑测试确认通过。

### Task 2: 确保 null 字段真实持久化

**Files:**
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelinePersistenceTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/ai/PipelineRun.java`

**Interfaces:**
- Produces: `PipelineRun.activeConversationId` 使用 `FieldStrategy.ALWAYS`，确保 `updateById` 包含 `active_conversation_id = NULL`。

- [x] 增加失败测试：实体字段必须声明 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`。
- [x] 运行 `PipelinePersistenceTests`，确认注解缺失导致失败。
- [x] 添加 MyBatis-Plus 字段更新策略。
- [x] 运行相关测试和后端打包。
- [x] 检查差异并使用中文 Commit Message 提交。
