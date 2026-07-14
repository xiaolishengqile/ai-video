# AgentScope Empty Tool Call Defense Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent required-parameter tools with aggregated `{}` input from reaching execution or the UI while preserving valid calls and legitimate zero-argument tools.

**Architecture:** Add a high-priority `PostReasoningEvent` hook that inspects the already-aggregated reasoning message against the active Toolkit schemas. It removes invalid empty calls before `PreActingEvent`; if no valid tool call remains, it requests another reasoning turn with a compact correction message.

**Tech Stack:** Java 17-compatible source, AgentScope Java 1.0.12, Reactor `Mono`, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Do not change the configured model, gateway, streaming mode, or parallel execution.
- Do not block in production reactive code.
- Preserve tools whose schema has no required parameters.
- Log tool name, call ID, and agent name only; never log tool arguments.
- Use test-first red-green cycles before production edits.

---

### Task 1: Filter aggregated ghost tool calls

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/EmptyToolCallFilterHook.java`
- Create: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/EmptyToolCallFilterHookTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeAssistantService.java:180-193, 775-810`

**Interfaces:**
- Consumes: `Toolkit#getTool(String)`, `AgentTool#getParameters()`, `PostReasoningEvent#getReasoningMessage()`.
- Produces: `EmptyToolCallFilterHook(Toolkit)` implementing `Hook`, with priority `50`.

- [ ] **Step 1: Write failing hook tests**

Create tests that register required and zero-argument `AgentTool` stubs, build `PostReasoningEvent` messages, call the hook, and assert:

```java
assertThat(event.getReasoningMessage().getContentBlocks(ToolUseBlock.class))
        .extracting(ToolUseBlock::getId)
        .containsExactly("valid-call");
assertThat(event.isGotoReasoningRequested()).isFalse();
```

For an all-invalid response:

```java
assertThat(event.isGotoReasoningRequested()).isTrue();
assertThat(event.getGotoReasoningMsgs().getFirst().getTextContent())
        .contains("required 参数");
```

For a legal no-argument tool:

```java
assertThat(event.getReasoningMessage().getContentBlocks(ToolUseBlock.class))
        .extracting(ToolUseBlock::getId)
        .containsExactly("no-arg-call");
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd ai-fusion-video
./mvnw -Dtest=EmptyToolCallFilterHookTests test
```

Expected: compilation failure because `EmptyToolCallFilterHook` does not exist.

- [ ] **Step 3: Implement the minimal hook**

Implement `onEvent` with this behavior:

```java
if (!(event instanceof PostReasoningEvent reasoningEvent)) {
    return Mono.just(event);
}

List<ContentBlock> filtered = reasoningMessage.getContent().stream()
        .filter(block -> !(block instanceof ToolUseBlock toolUse) || !isInvalidEmptyCall(toolUse))
        .toList();
```

`isInvalidEmptyCall` returns true only when input is null/empty and the registered tool schema contains a non-empty `required` collection. Rebuild the immutable `Msg` when filtering. If no valid `ToolUseBlock` remains, call `gotoReasoning` with a `MsgRole.USER` correction message.

- [ ] **Step 4: Integrate the hook before the streaming hook**

For the main Agent:

```java
.hooks(List.of(new EmptyToolCallFilterHook(toolkit), streamingHook));
```

For each sub-Agent, create the filter from that sub-Agent's own Toolkit and use the same hook order. Agents without a Toolkit retain only `streamingHook`.

- [ ] **Step 5: Run focused tests and commit**

Run:

```bash
cd ai-fusion-video
./mvnw -Dtest=EmptyToolCallFilterHookTests,AgentScopeToolAdapterTests test
```

Expected: all selected tests pass.

Commit files with message `fix(ai): filter empty required tool calls`.

---

### Task 2: Align script schemas and task context

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptEpisodeToolExecutor.java:45-81`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java:151-153`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md:8-20`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptEpisodeToolExecutorTests.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/config/ai/AiAgentRegistryTests.java`

**Interfaces:**
- Produces: `save_script_episode` schema required fields `scriptId`, `episodeNumber`, `title`.
- Produces: `script_full_parse` instruction template with `<project_id>{projectId}</project_id>` and `<script_id>{scriptId}</script_id>`.

- [ ] **Step 1: Write failing schema and registry tests**

Add assertions:

```java
assertThat(JSONUtil.parseObj(executor.getParametersSchema()).getJSONArray("required"))
        .containsExactly("scriptId", "episodeNumber", "title");
```

```java
AiAgentDefinition definition = new AiAgentRegistry().getByType("script_full_parse");
assertThat(definition.getInstructionTemplate())
        .contains("{projectId}", "{scriptId}")
        .doesNotContain("{scriptContent}");
assertThat(definition.getSystemPrompt()).contains("所有写操作").contains("scriptId");
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd ai-fusion-video
./mvnw -Dtest=SaveScriptEpisodeToolExecutorTests,AiAgentRegistryTests test
```

Expected: assertions fail because the schema has an empty required list and the instruction still contains `{scriptContent}`.

- [ ] **Step 3: Apply the minimal configuration fixes**

Change the schema to:

```json
"required": ["scriptId", "episodeNumber", "title"]
```

Change the instruction template to:

```xml
<task_context>
<project_id>{projectId}</project_id>
<script_id>{scriptId}</script_id>
</task_context>
```

Add one ID rule stating that every `update_script_info`, `save_script_episode`, `run_script_asset_prebinding`, and snapshot write must pass the task's current `scriptId`.

- [ ] **Step 4: Run focused tests and commit**

Run:

```bash
cd ai-fusion-video
./mvnw -Dtest=SaveScriptEpisodeToolExecutorTests,AiAgentRegistryTests test
```

Expected: all selected tests pass.

Commit files with message `fix(script): require parse task identifiers`.

---

### Task 3: Verify the integrated backend

**Files:**
- Verify only; modify production files only if a test exposes a regression directly caused by Tasks 1-2.

**Interfaces:**
- Consumes all Task 1-2 changes.
- Produces fresh build and test evidence.

- [ ] **Step 1: Run all backend tests**

```bash
cd ai-fusion-video
./mvnw test
```

Expected: Maven exits `0` with zero failures and zero errors.

- [ ] **Step 2: Run compile/package verification**

```bash
cd ai-fusion-video
./mvnw -DskipTests package
```

Expected: Maven exits `0` and reports `BUILD SUCCESS`.

- [ ] **Step 3: Inspect the final diff**

```bash
git diff --check
git status --short
git log --oneline -5
```

Expected: no whitespace errors; only planned implementation files are changed or committed.
