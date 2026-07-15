# AI Pipeline 通用检查点与断点续跑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为全部 AI Pipeline 增加模型失败后 5 次重试、步骤级持久化检查点、瞬时故障自动续跑一次、人工继续和历史失败任务恢复。

**Architecture:** 以 `PipelineRun` 作为用户可见逻辑任务，复用 `AgentConversation` 表示每次执行尝试，以 `PipelineCheckpoint` 记录有副作用工具的稳定业务步骤。`PipelineRuntimeService` 负责状态机和自动恢复，`PipelineCheckpointPolicyRegistry` 负责工具幂等策略，`PipelineResumeStrategyRegistry` 负责把检查点转换为精简恢复上下文；AgentScope 主服务仅接入这些组件。

**Tech Stack:** Java 26、Spring Boot 3.5、AgentScope Java 1.0.12、Project Reactor、MyBatis-Plus、Flyway、MySQL 8、Redis、Next.js 16、React 19、Zustand、Node test runner。

## Global Constraints

- 直接在 `main` 分支工作，不创建 worktree 或功能分支。
- 模型失败后最多重试 5 次，即 `maxAttempts=6`；仅重试 AgentScope 认定的瞬时错误。
- 每个逻辑 Pipeline 最多自动续跑 1 次；业务错误、鉴权错误、请求错误、上下文超限和未知错误不自动续跑。
- 续跑创建新 Agent 和新 conversation，不恢复旧 Agent 内存。
- 未分类或不可验证的有副作用工具禁止自动重放，安全降级到人工处理。
- MySQL 是运行状态和检查点的最终事实来源；Redis 只承担实时流和同一 `runId` 的执行锁。
- 生产代码不得调用 `.block()`、`Thread.sleep()` 或 `ThreadLocal`。
- 每个任务必须先完成失败测试，再实现，再运行相关测试，并使用中文 Commit Message 提交。
- 当前已有全量测试基线包含与本功能无关的失败；每次任务必须保证本任务定向测试通过，并在最终验证中明确区分基线失败。

---

### Task 1: 统一模型 5 次重试并保留根因

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/provider/OpenAiCompatibleAiProvider.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/provider/OpenAiResponsesAgentScopeModel.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailure.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailureCategory.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailureClassifier.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/provider/OpenAiCompatibleAiProviderTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailureClassifierTests.java`

**Interfaces:**
- Produces: `PipelineFailure PipelineFailureClassifier.classify(Throwable error)`。
- Produces: `PipelineFailure(category, code, message, retryable)`，其中 message 已脱敏并保留最深层根因。
- Produces: `GenerateOptions` 中的 `ExecutionConfig.maxAttempts == 6`。

- [ ] **Step 1: 为 Chat Completions 重试配置写失败测试**

在 `OpenAiCompatibleAiProviderTests` 中通过反射读取返回 `OpenAIChatModel` 的默认 `GenerateOptions`，断言：

```java
assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(6);
assertThat(options.getExecutionConfig().getInitialBackoff()).isEqualTo(Duration.ofSeconds(2));
assertThat(options.getExecutionConfig().getMaxBackoff()).isEqualTo(Duration.ofSeconds(30));
```

- [ ] **Step 2: 为错误分类写失败测试**

覆盖 `RateLimitException`、`TimeoutException`、带 retryable transport cause 的包装异常、BadRequest、401 文本、业务异常和未知异常；断言只有前四类瞬时故障的 `retryable` 为 true，并断言包装异常返回最深层有效消息而不是 `Retries exhausted`。

- [ ] **Step 3: 运行测试确认失败**

Run:

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=OpenAiCompatibleAiProviderTests,PipelineFailureClassifierTests test
```

Expected: FAIL，缺少分类器且默认 `maxAttempts` 为 3。

- [ ] **Step 4: 实现最小重试配置和错误分类器**

在 provider 的 `buildGenerateOptions` 中始终合并：

```java
ExecutionConfig modelExecution = ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(5))
        .maxAttempts(6)
        .initialBackoff(Duration.ofSeconds(2))
        .maxBackoff(Duration.ofSeconds(30))
        .backoffMultiplier(2.0)
        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
        .build();
builder.executionConfig(modelExecution);
```

将 Responses 客户端 `.maxRetries(2)` 改为 `.maxRetries(5)`。分类器沿 cause 链识别 AgentScope 异常和 HTTP 状态，未知异常返回 `UNKNOWN/retryable=false`。

- [ ] **Step 5: 运行定向测试并提交**

Run 同 Step 3，Expected: PASS。

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/provider ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/provider ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline
git commit -m "功能：统一 AI 模型重试和错误分类"
```

### Task 2: 建立逻辑任务与检查点持久化模型

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.5__ai_pipeline_checkpoint_resume.sql`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/ai/PipelineRun.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/ai/PipelineCheckpoint.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/mapper/ai/PipelineRunMapper.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/mapper/ai/PipelineCheckpointMapper.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/ai/AgentConversation.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/AgentConversationService.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineRunStatus.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineResumeType.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineCheckpointStatus.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineRunRepository.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineCheckpointRepository.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelinePersistenceTests.java`

**Interfaces:**
- Produces: `PipelineRun create(AiChatReqVO request, Long userId)`。
- Produces: `PipelineRun requireByRunId(String runId)`。
- Produces: `PipelineCheckpoint upsertRunning(Long runId, CheckpointDescriptor descriptor, String inputJson)`。
- Produces: `markSucceeded`, `markFailed`, `markUnknown` 和 `listByRunId`。

- [ ] **Step 1: 写 Flyway 与仓储失败测试**

测试实体表名、唯一键语义和仓储 upsert：相同 `(pipeline_run_id, checkpoint_key)` 第二次写入只增加 `attemptCount`，不新建第二行。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=PipelinePersistenceTests test
```

Expected: FAIL，实体、Mapper 和表不存在。

- [ ] **Step 3: 创建迁移和精简实体**

迁移创建 `afv_ai_pipeline_run`、`afv_ai_pipeline_checkpoint`，并给 `afv_agent_conversation` 增加：

```sql
`pipeline_run_id` bigint DEFAULT NULL,
`attempt_number` int NOT NULL DEFAULT 0,
`resume_type` varchar(16) NOT NULL DEFAULT 'INITIAL'
```

检查点建立唯一键 `uk_pipeline_checkpoint (pipeline_run_id, checkpoint_key)`；run 建唯一键 `uk_pipeline_run_id (run_id)` 和索引 `(status, update_time)`。

- [ ] **Step 4: 实现仓储服务**

仓储只负责数据库读写，不放置状态机判断。通过 MyBatis-Plus 条件更新和唯一键实现并发安全 upsert；输入输出写入前调用统一 JSON 裁剪器，限制单字段最大 64 KiB。

- [ ] **Step 5: 运行迁移和测试并提交**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=PipelinePersistenceTests test
```

Expected: PASS。

```bash
git add ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.5__ai_pipeline_checkpoint_resume.sql ai-fusion-video/src/main/java/com/stonewu/fusion/entity/ai ai-fusion-video/src/main/java/com/stonewu/fusion/mapper/ai ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/AgentConversationService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline
git commit -m "功能：持久化 AI Pipeline 运行和检查点"
```

### Task 3: 实现通用状态机、自动续跑额度和单任务锁

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineRuntimeService.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineRunLock.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineAttempt.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineRuntimeServiceTests.java`

**Interfaces:**
- Produces: `PipelineAttempt startInitial(AiChatReqVO request, Long userId)`。
- Produces: `Mono<PipelineAttempt> handleFailure(String runId, String conversationId, Throwable error)`。
- Produces: `PipelineAttempt startManualResume(String runId, Long userId)`。
- Produces: `void complete/cancel(String runId, String conversationId)`。

- [ ] **Step 1: 写状态机失败测试**

覆盖：首次完成；瞬时错误产生 AUTO attempt 且 `autoResumeCount=1`；第二次瞬时错误进入 `WAITING_MANUAL_RESUME`；业务错误进入 `FAILED_NON_RETRYABLE`；取消后不恢复；并发 resume 只有一个获得锁。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=PipelineRuntimeServiceTests test
```

Expected: FAIL，状态机未实现。

- [ ] **Step 3: 实现非阻塞状态机和 Redis 锁**

`PipelineAttempt` 只携带：

```java
public record PipelineAttempt(
        String runId,
        String conversationId,
        int attemptNumber,
        PipelineResumeType resumeType,
        AiChatReqVO request) {}
```

锁 key 为 `fv:ai:pipeline:lock:{runId}`，使用带随机 owner token 的 `setIfAbsent` 和比较后删除；自动恢复使用 `Mono.delay(Duration.ofSeconds(5))`，禁止 `Thread.sleep()`。

- [ ] **Step 4: 运行测试并提交**

Run 同 Step 2，Expected: PASS。

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline
git commit -m "功能：实现 Pipeline 状态机和自动续跑"
```

### Task 4: 在工具适配层记录并保护检查点

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/CheckpointReplayPolicy.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/CheckpointDescriptor.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointPolicy.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointPolicyRegistry.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineExecutionContext.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeToolAdapter.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeSubAgentToolAdapter.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeToolAdapterTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointPolicyRegistryTests.java`

**Interfaces:**
- Produces: `Optional<CheckpointDescriptor> describe(String toolName, String inputJson)`。
- Produces: `CheckpointDecision beforeExecute(runId, descriptor)`，结果为 EXECUTE、RETURN_STORED 或 REQUIRE_MANUAL。
- Consumes: `PipelineExecutionContext(runId, conversationId, attemptNumber)`，由主服务显式传入，不能使用 ThreadLocal。

- [ ] **Step 1: 写工具检查点失败测试**

在现有 adapter 测试中加入：首次调用写 RUNNING/SUCCEEDED；同一 SAFE_REPLAY 检查点成功后返回存储输出且 executor 只执行一次；NEVER_REPLAY 的 UNKNOWN 检查点返回业务错误并阻止执行；取消仍优先终止。

- [ ] **Step 2: 写策略注册表完整性测试**

读取所有 Pipeline Agent 的普通工具和子 Agent 工具，断言每个有副作用工具都有显式策略；只读工具允许 `Optional.empty()`。策略至少覆盖：

```text
save_script_episode, save_script_scene_items, update_script_info,
run_script_asset_prebinding, create_project_asset_catalog_snapshot,
save_storyboard_episode, save_storyboard_scene_shots,
insert_storyboard_item, update_storyboard_item_frame,
update_storyboard_item_video, update_storyboard_item_workflow,
create_asset, update_asset, add_asset_item,
batch_create_assets, batch_create_asset_items, update_asset_image,
resolve_scene_entity_manifest, manage_script_scenes,
update_script, update_script_scene, generate_image, generate_video
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AgentScopeToolAdapterTests,PipelineToolCheckpointPolicyRegistryTests test
```

Expected: FAIL，适配器没有 Pipeline 上下文和检查点保护。

- [ ] **Step 4: 实现策略与 adapter 接入**

数据库 update/save 类优先 `SAFE_REPLAY`；图片/视频生成使用 `VERIFY_BEFORE_REPLAY`；无法验证的创建类使用 `NEVER_REPLAY`。checkpoint key 使用业务 ID，例如：

```java
return new CheckpointDescriptor(
        "save_script_episode:" + scriptId + ":" + episodeNumber,
        "episode",
        scriptId + ":" + episodeNumber,
        CheckpointReplayPolicy.SAFE_REPLAY);
```

工具返回 JSON 中 `status=error` 时必须标记 FAILED，不能记录为 SUCCEEDED。

- [ ] **Step 5: 运行测试并提交**

Run 同 Step 3，Expected: PASS。

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline
git commit -m "功能：为 Pipeline 工具增加幂等检查点"
```

### Task 5: 生成恢复计划并重建历史任务检查点

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineResumePlan.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineResumeStrategy.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineResumeStrategyRegistry.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/ToolCheckpointResumeStrategy.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/LegacyPipelineCheckpointImporter.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/ScriptFullParseResumeStrategy.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/AgentMessageService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/LegacyPipelineCheckpointImporterTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/ScriptFullParseResumeStrategyTests.java`

**Interfaces:**
- Produces: `PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints)`。
- Produces: `String PipelineResumePlan.toPromptBlock()`，输出 `<resume_context>`。
- Produces: `PipelineRun importConversation(String conversationId, Long userId)`。

- [ ] **Step 1: 写剧本恢复计划失败测试**

构造 script 40 的状态：第 1-12 集存在，第 1 集有预绑定，场次和快照为空；断言恢复计划已完成列表和待完成列表与设计文档一致，并禁止重新创建第 1-12 集。

- [ ] **Step 2: 写历史导入失败测试**

构造同一 `tool_call_id` 的 running 输入消息和 success 输出消息，断言 importer 合并输入输出并生成稳定 checkpoint；孤立 running 记录生成 UNKNOWN；已有 pipelineRunId 时导入幂等。

- [ ] **Step 3: 运行测试确认失败**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=LegacyPipelineCheckpointImporterTests,ScriptFullParseResumeStrategyTests test
```

Expected: FAIL，恢复策略和 importer 不存在。

- [ ] **Step 4: 实现通用和剧本恢复策略**

通用策略从检查点生成“已完成/待执行/禁止重复”清单。`ScriptFullParseResumeStrategy` 额外查询剧本分集、预绑定、场次和快照作为业务验证。输出示例：

```xml
<resume_context>
  <completed>剧本元信息；第1-12集；第1集资产预绑定</completed>
  <pending>第13-20集；第2-20集资产预绑定；全部场次；全部快照</pending>
  <constraints>不得删除或重新创建第1-12集</constraints>
</resume_context>
```

- [ ] **Step 5: 运行测试并提交**

Run 同 Step 3，Expected: PASS。

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/AgentMessageService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline
git commit -m "功能：支持 Pipeline 恢复计划和历史进度重建"
```

### Task 6: 将通用运行时接入 AgentScope Pipeline API

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeAssistantService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/ai/AiPipelineController.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/ai/vo/AiChatStreamRespVO.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/ai/vo/PipelineStatusRespVO.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntimeTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/controller/ai/AiPipelineControllerTests.java`

**Interfaces:**
- Adds SSE field: `pipelineRunId`。
- Adds: `POST /api/ai/pipeline/{runId}/resume`。
- Adds: `POST /api/ai/pipeline/{runId}/cancel`。
- Adds: `GET /api/ai/pipeline/{runId}/status`。
- Adds: `GET /api/ai/pipeline/{runId}/reconnect`。
- Keeps existing conversationId endpoints for compatibility。

- [ ] **Step 1: 写服务接入失败测试**

断言首次 run 创建 run + INITIAL conversation；瞬时失败触发 AUTO conversation 并注入 resume context；业务失败不创建 AUTO conversation；完成时逻辑 run 与 conversation 同时完成。

- [ ] **Step 2: 写 Controller 鉴权和状态失败测试**

断言只有 run 所属用户可 resume/cancel/status/reconnect；重复 resume 返回当前活动 attempt，不创建并发 attempt。

- [ ] **Step 3: 运行测试确认失败**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AgentScopePipelineRuntimeTests,AiPipelineControllerTests test
```

Expected: FAIL，新 API 和接入不存在。

- [ ] **Step 4: 抽取单次 attempt 执行方法并接入状态机**

将当前 `stream()` 内部“构建 Agent、启动事件流、结束状态回写”抽为私有或独立 `executeAttempt(PipelineAttempt attempt)`，保持 Reactor 链非阻塞。所有流事件添加 `pipelineRunId`；自动续跑事件写入同一逻辑 run 的 Redis replay timeline。

- [ ] **Step 5: 实现新 API 并保持旧 API**

旧 `/run` 仍接收现有 `AiChatReqVO`；首次 SSE 事件返回 `pipelineRunId`。新 status 返回：逻辑状态、活动 conversation、attemptNumber、resumeType、自动续跑次数和脱敏错误。

- [ ] **Step 6: 运行测试并提交**

Run 同 Step 3，Expected: PASS。

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeAssistantService.java ai-fusion-video/src/main/java/com/stonewu/fusion/controller/ai ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope ai-fusion-video/src/test/java/com/stonewu/fusion/controller/ai
git commit -m "功能：接入 Pipeline 自动续跑和人工继续接口"
```

### Task 7: 前端按逻辑任务展示自动续跑和继续按钮

**Files:**
- Modify: `ai-fusion-video-web/lib/api/ai-assistant.ts`
- Modify: `ai-fusion-video-web/lib/api/ai-pipeline.ts`
- Modify: `ai-fusion-video-web/lib/store/pipeline-store.ts`
- Modify: `ai-fusion-video-web/components/dashboard/notification-panel/detail.tsx`
- Modify: `ai-fusion-video-web/components/dashboard/notification-panel/timeline.tsx`
- Create: `ai-fusion-video-web/lib/pipeline-resume-state.mjs`
- Create: `ai-fusion-video-web/lib/pipeline-resume-state.test.mjs`

**Interfaces:**
- Adds event property: `pipelineRunId?: string`。
- Adds API: `resumePipeline(runId)`, `cancelPipelineRun(runId)`, `getPipelineRunStatus(runId)`, `reconnectPipelineRun(runId, callbacks)`。
- Adds task state: `pipelineRunId`, `attemptNumber`, `resumeType`, `autoResumeCount`, `canResume`。

- [ ] **Step 1: 写纯状态转换失败测试**

使用 Node test runner 验证 `AUTO_RESUMING` 保持卡片 running、`WAITING_MANUAL_RESUME` 进入 error 且 canResume、`FAILED_NON_RETRYABLE` 显示修正后继续、多个 attempt 仍对应同一卡片。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd ai-fusion-video-web
node --test lib/pipeline-resume-state.test.mjs
```

Expected: FAIL，状态转换模块不存在。

- [ ] **Step 3: 实现 API、store 和 UI**

任务卡片优先使用 `pipelineRunId` 重连；兼容没有 runId 的旧 conversation。增加以下时间线文案：

```text
模型请求重试 3/5
模型重试已耗尽，5 秒后从检查点自动续跑 1/1
自动续跑开始
自动续跑仍失败，等待手动继续
```

自动续跑阶段禁用继续按钮；人工继续成功后清空旧 error，但保留已有 timeline。

- [ ] **Step 4: 运行前端测试、lint 和构建**

```bash
cd ai-fusion-video-web
node --test lib/pipeline-resume-state.test.mjs
pnpm lint
pnpm build
```

Expected: test PASS，lint 和 build 成功。

- [ ] **Step 5: 提交**

```bash
git add ai-fusion-video-web/lib ai-fusion-video-web/components/dashboard/notification-panel
git commit -m "功能：展示 Pipeline 自动续跑和人工继续"
```

### Task 8: 故障注入、历史任务实测与最终验证

**Files:**
- Create: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineResumeIntegrationTests.java`
- Modify: `docs/superpowers/specs/2026-07-15-ai-pipeline-checkpoint-resume-design.md`（仅在实现与已确认设计存在必要差异时更新）

**Interfaces:**
- Verifies end-to-end contracts produced by Tasks 1-7。

- [ ] **Step 1: 写故障注入集成测试**

覆盖四类代表流程：剧本保存后失败、图片远端任务提交后失败、视频远端任务提交后失败、业务参数错误。断言前三者恢复时不重复副作用，业务错误不自动续跑。

- [ ] **Step 2: 验证历史任务导入**

针对 conversation `f8f3681bf0534a5c8b5f87ee1afc0ec1` 执行 importer 的只读预览，断言识别：

```text
scriptId=40
episodesSucceeded=1..12
prebindingSucceeded=1
sceneWritersSucceeded=0
snapshotsSucceeded=0
```

测试不得直接启动付费模型调用；只验证恢复计划和数据库检查点。

- [ ] **Step 3: 运行后端定向测试**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest='Pipeline*Tests,AgentScopeToolAdapterTests,AgentScopePipelineRuntimeTests,AiPipelineControllerTests,OpenAiCompatibleAiProviderTests' test
```

Expected: PASS。

- [ ] **Step 4: 运行后端完整测试和打包**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw test
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -DskipTests package
```

Expected: package PASS；完整测试若仍有基线失败，必须与改动前基线逐项一致，不得新增失败。

- [ ] **Step 5: 运行前端完整验证**

```bash
cd ai-fusion-video-web
node --test lib/*.test.mjs app/'(dashboard)'/assets/*.test.cjs
pnpm lint
pnpm build
```

Expected: 全部成功。

- [ ] **Step 6: 检查工作树和提交最终测试**

```bash
git diff --check
git status --short
git add ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline docs/superpowers/specs/2026-07-15-ai-pipeline-checkpoint-resume-design.md
git commit -m "测试：验证 AI Pipeline 断点续跑"
```

最终结果必须报告：提交列表、定向测试、完整测试基线差异、前端构建结果，以及当前历史任务是否已生成可执行恢复计划。
