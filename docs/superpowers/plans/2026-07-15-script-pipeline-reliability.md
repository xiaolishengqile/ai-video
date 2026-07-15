# 剧本解析 Pipeline 可靠性修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除分批场次保存误去重、授权错误误重试、无效工具参数和异常重复输出，确保剧本解析结果可核验。

**Architecture:** 保留现有 AgentScope Pipeline，只在共享检查点策略、错误分类、工具契约和流式输出边界增加最小防护。所有行为先以单元测试复现，再修改生产代码。

**Tech Stack:** Java 26、Spring Boot、AgentScope 1.0.12、JUnit 5、AssertJ、Mockito。

## Global Constraints

- 不新增依赖。
- 不修改现有数据库结构。
- 不扩大到剧本解析之外的业务流程。
- 每个修复必须有失败测试和通过测试证据。

---

### Task 1: 修复场次保存检查点碰撞

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointPolicyRegistry.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointPolicyRegistryTests.java`

- [x] 添加测试：同一分集、同一版本、不同 `scenes` 或 `overwriteMode` 必须生成不同检查点键。
- [x] 运行测试并确认因现有键碰撞失败。
- [x] 将输入摘要加入 `save_script_scene_items` 检查点键。
- [x] 运行检查点测试并确认通过。

### Task 2: 修复授权型 429 分类

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailureClassifier.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineFailureClassifierTests.java`

- [x] 添加测试：包含 `authorization failed` 或 `invalid api key` 的 429 必须归类为 `NON_RETRYABLE_AUTH`。
- [x] 运行测试并确认现有逻辑错误归类为限流。
- [x] 在通用 429 分支前识别授权语义。
- [x] 将模型层瞬时错误尝试上限收敛为 3 次，避免与 Pipeline 自动恢复叠加放大等待时间。
- [x] 运行分类测试并确认普通 429 仍可重试。

### Task 3: 收紧场次保存工具契约

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java`

- [x] 添加测试：Schema 必须要求 `scriptEpisodeId`、`episode_version`、`scenes`，且每批上限统一为 3。
- [x] 运行测试并确认现有空 `required` 与上限冲突导致失败。
- [x] 修改 Schema 和提示词，保持工具说明一致。
- [x] 运行工具测试并确认通过。

### Task 4: 过滤工具协议文本的异常流式输出

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/StreamingEventHook.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/StreamingEventHookTests.java`

- [x] 添加测试：DSML 工具协议文本不能作为助手正文流式发布和持久化。
- [x] 运行测试并确认现有实现错误地接受协议文本。
- [x] 在共享流式 Hook 中过滤工具协议文本块。
- [x] 运行 Hook 测试并确认正常正文不受影响。

### Task 5: 验证与提交

**Files:**
- Verify: all modified files and related tests

- [x] 运行相关测试类。
- [x] 运行后端完整测试套件。
- [x] 检查差异、敏感信息和工作区状态。
- [x] 使用中文提交信息提交完整修复。
