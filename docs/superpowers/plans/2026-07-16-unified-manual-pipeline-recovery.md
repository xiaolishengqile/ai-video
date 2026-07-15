# AI Pipeline 统一手动恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有拥有 `pipelineRunId` 的未完成中断任务始终提供同一个“继续”入口，并由后端依据最新状态选择重连、检查点续跑或卡住恢复。

**Architecture:** 保留现有 Pipeline 状态机和三个恢复动作，不新增状态或接口。前端统一调用 resume 接口并对错误/取消任务提供按钮兜底；后端 resume 入口补齐 `RECOVER_STALLED` 分派。

**Tech Stack:** Java 26、Spring Boot 3.5、Project Reactor、JUnit 5、Mockito、Next.js 16、React 19、Zustand、Node test runner。

## Global Constraints

- 自动恢复策略保持不变。
- `COMPLETED` 不允许恢复。
- 不新增数据库迁移、接口或依赖。
- 所有生产代码变更必须先有失败测试。
- 使用中文 Commit Message 提交功能完整的改动。

---

### Task 1: 后端 resume 入口覆盖卡住恢复

**Files:**
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntimeTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntime.java`

**Interfaces:**
- Consumes: `PipelineRecoveryPolicy.decide(...)` 返回的 `PipelineRecoveryAction`。
- Produces: `Flux<AiChatStreamRespVO> AgentScopePipelineRuntime.resume(String runId, Long userId)` 统一处理 `RESUME`、`RECONNECT`、`RECOVER_STALLED`。

- [ ] **Step 1: 写失败测试**

在 `AgentScopePipelineRuntimeTests` 将卡住恢复测试改为直接调用统一入口：

```java
StepVerifier.create(service.resume("run-1", 7L))
        .expectNextCount(1)
        .verifyComplete();
verify(assistant).cancelStream("conversation-1");
verify(runtime).startStalledResume("run-1", 7L);
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AgentScopePipelineRuntimeTests test
```

Expected: FAIL，`resume` 对 `RECOVER_STALLED` 返回 409。

- [ ] **Step 3: 写最小实现**

在 `AgentScopePipelineRuntime.resume` 中补充分派：

```java
if (action == PipelineRecoveryAction.RECONNECT) {
    return reconnect(runId, userId);
}
if (action == PipelineRecoveryAction.RECOVER_STALLED) {
    return recover(runId, userId);
}
if (action != PipelineRecoveryAction.RESUME) {
    throw new BusinessException(409, "当前 Pipeline 状态不能继续执行");
}
```

- [ ] **Step 4: 运行测试确认通过**

Run 同 Step 2，Expected: `AgentScopePipelineRuntimeTests` 全部通过。

### Task 2: 前端对所有中断任务保留统一继续入口

**Files:**
- Modify: `ai-fusion-video-web/lib/pipeline-resume-state.mjs`
- Modify: `ai-fusion-video-web/lib/pipeline-resume-state.test.mjs`
- Modify: `ai-fusion-video-web/lib/store/pipeline-store.ts`
- Modify: `ai-fusion-video-web/components/dashboard/notification-panel/detail.tsx`

**Interfaces:**
- Produces: `boolean canManuallyResumeTask(status, pipelineRunId)`。
- Consumes: `resumePipelineRun(runId, callbacks)` 统一恢复接口。

- [ ] **Step 1: 写失败测试**

```javascript
import {
  applyPipelineRunStatus,
  canManuallyResumeTask,
} from "./pipeline-resume-state.mjs";

test("any interrupted pipeline task keeps manual continue available", () => {
  assert.equal(canManuallyResumeTask("error", "run-1"), true);
  assert.equal(canManuallyResumeTask("cancelled", "run-1"), true);
  assert.equal(canManuallyResumeTask("done", "run-1"), false);
  assert.equal(canManuallyResumeTask("error", undefined), false);
});
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd ai-fusion-video-web
node --test lib/pipeline-resume-state.test.mjs
```

Expected: FAIL，缺少 `canManuallyResumeTask` 导出。

- [ ] **Step 3: 写最小实现并接入 UI/store**

```javascript
export function canManuallyResumeTask(status, pipelineRunId) {
  return Boolean(pipelineRunId) && (status === "error" || status === "cancelled");
}
```

在两个“继续”按钮和 store 的恢复 guard 中使用 `state.canResume || canManuallyResumeTask(...)`；`settleTaskIfRunning` 在 Pipeline 失败时设置 `canResume: true`。删除 `recoverPipelineRun` 分支，所有点击统一调用 `resumePipelineRun`，由后端分派最新动作。

- [ ] **Step 4: 运行测试与类型检查**

Run:

```bash
cd ai-fusion-video-web
node --test lib/pipeline-resume-state.test.mjs
pnpm exec tsc --noEmit
```

Expected: Node 测试全部通过，TypeScript 退出码为 0。

### Task 3: 联合验证与提交

**Files:**
- Verify: all files modified by Task 1 and Task 2

**Interfaces:**
- Consumes: Task 1 后端统一入口、Task 2 前端恢复资格函数。
- Produces: 可提交的完整恢复功能。

- [ ] **Step 1: 运行后端相关测试**

```bash
cd ai-fusion-video
JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AgentScopePipelineRuntimeTests,PipelineRuntimeServiceTests,PipelineRecoveryPolicyTests test
```

Expected: 测试全部通过，无失败或错误。

- [ ] **Step 2: 运行前端相关测试和类型检查**

```bash
cd ai-fusion-video-web
node --test lib/pipeline-resume-state.test.mjs
pnpm exec tsc --noEmit
```

Expected: 全部通过。

- [ ] **Step 3: 检查差异并提交**

```bash
git diff --check
git status --short
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntime.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopePipelineRuntimeTests.java ai-fusion-video-web/lib/pipeline-resume-state.mjs ai-fusion-video-web/lib/pipeline-resume-state.test.mjs ai-fusion-video-web/lib/store/pipeline-store.ts ai-fusion-video-web/components/dashboard/notification-panel/detail.tsx docs/superpowers/plans/2026-07-16-unified-manual-pipeline-recovery.md
git commit -m "修复 AI Pipeline 中断后统一手动恢复"
```

Expected: 中文提交成功，工作区无本任务未提交改动。
