# 版本化资产目录与确定性绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让剧本、场次与分镜在一次任务中使用同一份可追溯资产目录，并阻止并发解析创建重复主资产。

**Architecture:** 资产表增加规范化名称和唯一索引，服务端通过原子“查询或创建”复用主资产。新增持久化的项目资产目录快照及两个 Agent 工具；剧本、分镜父 Agent 创建快照，子 Agent 用快照 ID 读取同一份目录。现有 manifest 和镜头继承继续作为场次/镜头的事实来源。

**Tech Stack:** Spring Boot、MyBatis-Plus、Flyway、JUnit 5/Mockito、Next.js。

## Global Constraints

- 历史场次和分镜不回填、不改写。
- 同名活跃资产的唯一性按项目、类型、规范化名称保证；历史重复资产保留且不删除图片。
- 子 Agent 仍使用现有 `message` 输入协议，快照 ID 写入消息，避免破坏未改造的其他 Agent。

### Task 1: 主资产原子复用

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.1.9__asset_normalized_name_unique.sql`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/asset/Asset.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/BatchCreateAssetsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceTests.java`

- [ ] Write failing tests for whitespace/case-normalized lookup and duplicate-key fallback.
- [ ] Add migration that retains legacy duplicate rows by assigning noncanonical normalized names, then adds a live-asset unique index.
- [ ] Implement `findOrCreateByProjectTypeAndName` and route both creation paths through it.
- [ ] Run focused asset and manifest tests.

### Task 2: 持久化资产目录快照

**Files:**
- Create: migration, entity, mapper and service under `asset`.
- Create: `CreateProjectAssetCatalogSnapshotToolExecutor` and `GetProjectAssetCatalogSnapshotToolExecutor`.
- Modify: `AiAgentRegistry.java` and relevant prompts.
- Test: new `AssetCatalogSnapshotServiceTests` and tool tests.

- [ ] Write failing tests for immutable snapshot content and permission checks.
- [ ] Persist the project asset/item list as JSON and expose the snapshot ID through tools.
- [ ] Make script parsing create its snapshot after primary assets exist, then pass the ID to each scene writer.
- [ ] Run focused tests.

### Task 3: 分镜使用预处理后的快照

**Files:**
- Modify: storyboard registry entries and prompts.
- Test: `AiAgentRegistryTests`.

- [ ] Write failing registry tests for snapshot tools/messages.
- [ ] Make the asset preprocessor create the post-variant snapshot and pass it to episode storyboard writers.
- [ ] Run backend tests, TypeScript checks and production build.
