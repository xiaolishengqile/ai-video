# 资产目录与三类场次绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已上传资产在剧本和分镜解析中被稳定复用，并允许角色、场景、道具同时绑定。

**Architecture:** 复用现有 `list_project_assets`、`entityManifest`、镜头资产继承服务；修正运行时覆盖提示词，使所有子 Agent 走同一 manifest 路径。前端继续依据已持久化的资产 ID 展示图片和来源。

**Tech Stack:** Spring Boot、JUnit 5、Next.js、TypeScript。

## Global Constraints

- 不增加数据库表或第三方依赖。
- 场次三类资产独立计数，且只能绑定实际入场实体。
- 每项行为改动先有失败测试，再做最小实现。

### Task 1: 统一运行时剧本场次解析提示词

**Files:**
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse_episode-scene-writer.override.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/config/ai/AiAgentRegistryTests.java`

- [ ] 写测试，断言实际子 Agent 覆盖提示词要求 `list_project_assets`、`resolve_scene_entity_manifest`，并禁止旧字段直填。
- [ ] 运行该测试并确认失败。
- [ ] 将覆盖提示词改为与基础提示词一致：先读取资产目录，逐场独立判断角色/场景/道具，解析 manifest 后保存。
- [ ] 运行测试并确认通过。

### Task 2: 固化目录读取与三类资产绑定

**Files:**
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java`

- [ ] 写测试，验证同一 manifest 的场景、角色和道具都会解析且保留 ID。
- [ ] 运行测试并确认失败。
- [ ] 用独立上限的现有解析器完成最小实现，并在提示词中规定资产读取时点和空类别规则。
- [ ] 运行聚焦测试并确认通过。

### Task 3: 场次/镜头可见性与回归验证

**Files:**
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java`

- [ ] 写测试，验证 manifest 同时派生角色、场景、道具关联。
- [ ] 运行测试并确认失败。
- [ ] 补足三类资产及绑定来源在详情面板中的可见标签/图片引用。
- [ ] 运行后端聚焦测试、TypeScript 检查和生产构建。
