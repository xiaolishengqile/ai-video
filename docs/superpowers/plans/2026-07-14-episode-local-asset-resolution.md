# 分集本地资产解析与分镜参考闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 仅搜索当前剧集的文本命名资产，稳定绑定已有子图并将其传递至分镜和生成流程。

**Architecture:** 以纯 Java 名称匹配服务提供每集候选；场次解析可选择或自动复用唯一候选，并只在缺失时创建分集占位资产。完整解析停止提前创建无范围资产；在场次/变体准备完成后创建快照，分镜和生成过程消费该快照内的子资产引用。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5/Mockito、Next.js/TypeScript。

## Global Constraints

- 不使用图片 embedding、OCR 或新增第三方依赖。
- 不跨集搜索；查询和验证始终要求同项目、同剧集、同资产类型。
- 名称模糊仅限已知结尾词归一化，歧义不自动选择。
- 所有新增生产行为先写失败测试并验证 RED。
- 无图片子资产不得被视为生成模型参考图。

---

### Task 1: 分集名称候选搜索

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/EpisodeAssetCandidateService.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/EpisodeAssetCandidate.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/EpisodeAssetCandidateServiceTests.java`

**Interfaces:** `findCandidates(Long projectId, Integer episodeNumber, String type, String name)` returns only highest-score candidates from the current episode, annotated `exact` or `suffix_normalized`.

- [x] **Step 1: Write failing tests** for exact matching, `凌炽 ← 凌炽表情图`, and same-score ambiguity.
- [x] **Step 2: Run** `./mvnw -Dtest=EpisodeAssetCandidateServiceTests test`; expect compilation/test failure because the service does not exist.
- [x] **Step 3: Implement** the minimal candidate record/service and scoped asset-list method. Normalize whitespace/case and only remove documented trailing visual suffixes.
- [x] **Step 4: Run** the same test; expect PASS.

### Task 2: 让场次实体使用候选或显式选择

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SearchEpisodeAssetCandidatesToolExecutor.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutorTests.java`

**Interfaces:** entities may include optional `selectedAssetId`. `resolve` validates the selection or uses one unique candidate; ambiguous candidates do not create records; missing retained entities create current-episode placeholders.

- [x] **Step 1: Write failing tests** for suffix reuse, cross-episode exclusion, ambiguous no-create, and explicit selected asset validation.
- [x] **Step 2: Run** `./mvnw -Dtest=SceneEntityManifestServiceTests,ResolveSceneEntityManifestToolExecutorTests test`; expect failure against the exact-only resolver.
- [x] **Step 3: Implement** candidate-based resolution and the read-only AI search tool. Return source counters for all match modes.
- [x] **Step 4: Run** the focused tests; expect PASS.

### Task 3: 让完整解析和分集场次按本集搜索

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/config/ai/AiAgentRegistryTests.java`

**Interfaces:** `script_full_parse` has no asset-creation tool. `episode_scene_writer` receives `search_episode_asset_candidates`, searches before resolve, and the subagent parameter schema agrees with its message format.

- [x] **Step 1: Write failing registry/prompt assertions** that full parsing excludes `batch_create_assets` and the scene writer includes the new search tool.
- [x] **Step 2: Run** `./mvnw -Dtest=AiAgentRegistryTests test`; expect failure.
- [x] **Step 3: Implement** the minimal registry and prompt changes. Full parsing saves episodes before dispatching scene agents; it does not create project/global assets.
- [x] **Step 4: Run** the registry tests; expect PASS.

### Task 4: 快照时机与分镜子图引用

**Files:**
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard_asset-preprocessor.override.md`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/GetStoryboardSceneItemsToolExecutor.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/GetStoryboardSceneItemsToolExecutorTests.java`

**Interfaces:** snapshots are created after scene binding/variant preparation. Resolved image references expose parent asset identity and `hasImage`; multiple child items from one parent stay distinct.

- [x] **Step 1: Write failing tests** for two child references sharing one parent and for `hasImage=false` when URL is absent.
- [x] **Step 2: Run** `./mvnw -Dtest=GetStoryboardSceneItemsToolExecutorTests test`; expect failure because parent information is absent.
- [x] **Step 3: Implement** parent reference enrichment and prompt sequencing that prevents empty variants from being described as usable reference images.
- [x] **Step 4: Run** the focused test; expect PASS.

### Task 5: 闭环验收

**Files:**
- Test: existing focused tests from Tasks 1–4
- Verify: backend and frontend production build

- [x] **Step 1: Run backend regression**:
  `cd ai-fusion-video && ./mvnw -Dtest=EpisodeAssetCandidateServiceTests,SceneEntityManifestServiceTests,ResolveSceneEntityManifestToolExecutorTests,ProjectAssetCatalogSnapshotToolExecutorTests,AiAgentRegistryTests,SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests,GetStoryboardSceneItemsToolExecutorTests test`
- [x] **Step 2: Run frontend build**: `cd ai-fusion-video-web && pnpm build`.
- [x] **Step 3: Run static completion checks**: `git diff --check`, inspect tool registration/prompt names, and confirm no `batch_create_assets` remains in the full parser flow.
- [ ] **Step 4: Commit** implementation and documentation with `git commit -m "feat(asset): resolve episode-local AI asset references"`.
