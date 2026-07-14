# 分集资产目录与剧本分镜精准绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让角色、场景、道具只属于一个剧集，并让当前集剧本和分镜只绑定该集已上传资产。

**Architecture:** 在 `Asset` 增加 `episodeNumber`，文件夹导入从相对路径解析该值。按集过滤的目录快照供子 Agent 使用；场次 manifest 和镜头保存用同一集数校验 AssetItem 的父资产。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Flyway、JUnit 5 / Mockito、Next.js、TypeScript。

## Global Constraints

- 新导入资产必须有一个剧集范围；不支持项目通用或跨集共享资产。
- 同名资产可在不同集独立存在，但同集、同类型、同名资产拒绝重复。
- 文件名是资产名称；不增加 OCR、视觉识别或依赖。
- 未匹配实体保留文字描述和 `unmatched_episode_catalog` 来源，不自动创建或跨集复用。
- 生产行为先写失败测试再实现。

---

### Task 1: 分集资产字段、索引和查询

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.1__asset_episode_scope.sql`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/asset/Asset.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceTests.java`

**Interfaces:**
- `Asset#getEpisodeNumber(): Integer`
- `AssetService#findByProjectEpisodeTypeAndName(Long, Integer, String, String): Asset`
- `AssetService#listWithItemsByProjectEpisode(Long, Integer): List<Map<String, Object>>`

- [ ] **Step 1: Write the failing lookup test.**

```java
@Test
void scopedLookupIncludesEpisodeNumber() {
    when(assetMapper.selectOne(any())).thenReturn(Asset.builder()
            .id(8L).projectId(1L).episodeNumber(8).type("prop").name("能量核心").build());

    assertThat(service.findByProjectEpisodeTypeAndName(1L, 8, "prop", "能量核心"))
            .extracting(Asset::getEpisodeNumber).isEqualTo(8);
}
```

- [ ] **Step 2: Verify red.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceTests test`

Expected: compile failure because the field and scoped method do not exist.

- [ ] **Step 3: Implement the field, migration and exact lookup.**

```sql
ALTER TABLE afv_asset ADD COLUMN episode_number INT NULL COMMENT '所属剧集序号；NULL 为历史未归集资产' AFTER project_id;
ALTER TABLE afv_asset DROP INDEX uk_asset_project_type_normalized_deleted;
ALTER TABLE afv_asset ADD UNIQUE INDEX uk_asset_project_episode_type_normalized_deleted
  (project_id, episode_number, type, normalized_name, deleted);
```

Make `findOrCreate` and its duplicate-key retry call the new scoped lookup when `asset.episodeNumber != null`; leave the old unscoped API for historical UI code.

- [ ] **Step 4: Verify green and commit.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceTests test`

Expected: PASS.

```bash
git add ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.1__asset_episode_scope.sql ai-fusion-video/src/main/java/com/stonewu/fusion/entity/asset/Asset.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceTests.java
git commit -m "feat(asset): scope assets to episodes"
```

### Task 2: 从文件夹相对路径解析剧集并导入

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/AssetFolderImportPreviewItem.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetFolderImportService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/vo/AssetFolderImportResultVO.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportServiceTests.java`
- Modify: `ai-fusion-video-web/lib/api/asset.ts`
- Modify: `ai-fusion-video-web/components/dashboard/asset-folder-import-dialog.tsx`

**Interfaces:**
- Preview/result item adds `Integer episodeNumber` and nullable `String reason`.
- Each created root asset receives the parsed `episodeNumber`.

- [ ] **Step 1: Write failing import tests.**

```java
@Test
void importsChineseEpisodeFolderAsScopedAsset() {
    when(assetService.findByProjectEpisodeTypeAndName(1L, 8, "prop", "能量核心")).thenReturn(null);
    // reuse the existing storage/initial-item stubs

    AssetFolderImportResultVO result = service.importFiles(1L, 9L, "prop",
            List.of(png("能量核心.png")), List.of("道具图/第八集道具图/能量核心.png"));

    assertThat(result.results()).singleElement().extracting(Item::episodeNumber).isEqualTo(8);
    verify(assetService).create(argThat(asset -> asset.getEpisodeNumber() == 8));
}

@Test
void marksFileWithoutOneEpisodeFolderAsInvalid() {
    assertThat(service.preview(1L, 9L, "prop", List.of(
            new AssetFolderImportFile("道具图/能量核心.png", "能量核心.png"))))
            .singleElement().extracting(AssetFolderImportPreviewItem::reason)
            .isEqualTo("路径必须包含一个第 N 集目录");
}
```

- [ ] **Step 2: Verify red.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetFolderImportServiceTests test`

Expected: compile or assertion failure because paths are currently ignored.

- [ ] **Step 3: Implement the smallest parser.**

Scan path segments for `第\s*([0-9]+|[一二三四五六七八九十]+)\s*集`; convert Chinese numerals only from 1 to 99. Reject zero matches or multiple distinct matches. Use the parsed episode in root/variant parent lookups so same names never merge across episodes. Display `第 {episodeNumber} 集` and the failure reason in the existing preview dialog; block upload if any retained item is invalid.

- [ ] **Step 4: Verify green and commit.**

Run:

```bash
cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetFolderImportNameServiceTests,AssetFolderImportServiceTests test
cd ../ai-fusion-video-web && pnpm build
```

Expected: PASS.

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImport* ai-fusion-video-web/lib/api/asset.ts ai-fusion-video-web/components/dashboard/asset-folder-import-dialog.tsx
git commit -m "feat(asset): import episode-scoped folders"
```

### Task 3: 向每个子 Agent 提供本集固定资产快照

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.2__asset_catalog_snapshot_episode.sql`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/asset/AssetCatalogSnapshot.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetCatalogSnapshotService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/CreateProjectAssetCatalogSnapshotToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard_episode-storyboard-writer.override.md`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetCatalogSnapshotServiceTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/config/ai/AiAgentRegistryTests.java`

**Interfaces:**
- `AssetCatalogSnapshot#scriptEpisodeId` identifies the one episode.
- `AssetCatalogSnapshotService#createForEpisode(Long projectId, Long scriptId, Long scriptEpisodeId, Integer episodeNumber)` serializes `listWithItemsByProjectEpisode` only.
- Snapshot creation tool requires `projectId`, `scriptId`, `scriptEpisodeId` and validates that the episode belongs to the script/project.

- [ ] **Step 1: Write failing snapshot and prompt tests.**

```java
@Test
void createsSnapshotWithOnlyTheRequestedEpisodeAssets() {
    when(assetService.listWithItemsByProjectEpisode(11L, 8)).thenReturn(List.of(Map.of("id", 81L)));

    AssetCatalogSnapshot snapshot = snapshotService.createForEpisode(11L, 7L, 70L, 8);

    assertThat(snapshot.getScriptEpisodeId()).isEqualTo(70L);
    assertThat(snapshot.getCatalogJson()).contains("81");
}
```

Assert the full-parse and storyboard prompts create a snapshot after obtaining each `scriptEpisodeId`, then pass that snapshot ID only to that episode’s sub-agent.

- [ ] **Step 2: Verify red.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetCatalogSnapshotServiceTests,AiAgentRegistryTests test`

Expected: compile failure for `scriptEpisodeId`/`createForEpisode`, or prompt assertion failure.

- [ ] **Step 3: Implement per-episode snapshots.**

```sql
ALTER TABLE afv_asset_catalog_snapshot
  ADD COLUMN script_episode_id BIGINT NULL COMMENT '固定资产目录对应的剧本分集' AFTER script_id;
```

Make snapshot creation filter by episode. The parent agents must create one snapshot per saved/existing script episode and pass its ID to the scene or storyboard writer; writers continue using the persisted snapshot and never fall back to the project-wide list in the new flow.

- [ ] **Step 4: Verify green and commit.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetCatalogSnapshotServiceTests,ProjectAssetCatalogSnapshotToolExecutorTests,AiAgentRegistryTests test`

Expected: PASS.

```bash
git add ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.2__asset_catalog_snapshot_episode.sql ai-fusion-video/src/main/java/com/stonewu/fusion/entity/asset/AssetCatalogSnapshot.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetCatalogSnapshotService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/CreateProjectAssetCatalogSnapshotToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java ai-fusion-video/src/main/resources/prompts/agents ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetCatalogSnapshotServiceTests.java ai-fusion-video/src/test/java/com/stonewu/fusion/config/ai/AiAgentRegistryTests.java
git commit -m "feat(ai): snapshot assets per episode"
```

### Task 4: 将当前集精确匹配资产写入场次 manifest

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-episode-upload.system.md`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java`

**Interfaces:**
- `SceneEntityManifestService#resolve(Long projectId, Long userId, Integer episodeNumber, SceneEntityManifest requested)` only resolves exact scoped assets.
- Resolver tool requires `scriptEpisodeId`; unmatched retained entities have source `unmatched_episode_catalog` and null IDs.

- [ ] **Step 1: Write failing exact-match tests.**

```java
@Test
void leavesMissingEntityUnboundInsteadOfCreatingIt() {
    when(assetService.findByProjectEpisodeTypeAndName(1L, 8, "prop", "能量核心")).thenReturn(null);

    SceneEntity entity = service.resolve(1L, 9L, 8, manifest("能量核心", "prop"))
            .entities().getFirst();

    assertThat(entity.source()).isEqualTo("unmatched_episode_catalog");
    assertThat(entity.assetId()).isNull();
    verify(assetService, never()).create(any());
}
```

- [ ] **Step 2: Verify red.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=SceneEntityManifestServiceTests,SaveScriptSceneItemsToolExecutorTests test`

Expected: failure because the existing resolver calls `findOrCreate`.

- [ ] **Step 3: Implement strict resolution and safe persistence.**

For each retained entity, look up only `(projectId, episodeNumber, type, name)`. When it is absent or has no initial item, retain the text entity with source `unmatched_episode_catalog`; do not fill legacy asset ID fields. Require `scriptEpisodeId` in the resolver tool and derive its episode number server-side. Update parsing prompts to call it with that ID.

- [ ] **Step 4: Verify green and commit.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=SceneEntityManifestServiceTests,ResolveSceneEntityManifestToolExecutorTests,SaveScriptSceneItemsToolExecutorTests test`

Expected: PASS.

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java ai-fusion-video/src/main/resources/prompts/agents/script-episode-upload.system.md ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java
git commit -m "feat(script): bind scenes to episode assets"
```

### Task 5: 分镜继承场次资产并拒绝跨集 ID

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptSceneItemDetailQueryToolExecutor.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx`

**Interfaces:**
- `validateItems(..., Integer episodeNumber)` rejects AssetItems whose parent Asset has a different episode number.
- Scene detail exposes only matched `default*AssetItemIds`.

- [ ] **Step 1: Write the failing cross-episode item test.**

```java
@Test
void saveRejectsExplicitItemFromAnotherEpisode() {
    when(assetService.getById(90L)).thenReturn(Asset.builder().id(90L)
            .projectId(1L).episodeNumber(9).type("prop").build());

    assertThat(executeShotWithExplicitProp(901L)).contains("资产不属于当前剧集");
}
```

- [ ] **Step 2: Verify red.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=SaveStoryboardSceneShotsToolExecutorTests test`

Expected: failure because validation currently checks only project and type.

- [ ] **Step 3: Implement episode validation and visible labels.**

Derive the expected episode number from the script episode in `SceneContext` and pass it into validation for inherited and explicit items. Keep existing defaults/exclusions: a shot inherits only matched core entities and may exclude off-screen entities. Add a compact `第 N 集` label in scene and storyboard asset panels.

- [ ] **Step 4: Verify green and commit.**

Run:

```bash
cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=SaveStoryboardSceneShotsToolExecutorTests test
cd ../ai-fusion-video-web && pnpm build
```

Expected: PASS.

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptSceneItemDetailQueryToolExecutor.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx
git commit -m "feat(storyboard): validate episode-scoped assets"
```

### Task 6: Full verification

- [ ] **Step 1: Check migration/entity/service consistency.**

Run: `git diff main...HEAD --check && rg -n "episode_number|script_episode_id" ai-fusion-video/src/main ai-fusion-video/src/test`

Expected: no whitespace errors and every scoped operation uses the same fields.

- [ ] **Step 2: Run affected backend tests.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceTests,AssetFolderImportNameServiceTests,AssetFolderImportServiceTests,AssetCatalogSnapshotServiceTests,ProjectAssetCatalogSnapshotToolExecutorTests,AiAgentRegistryTests,SceneEntityManifestServiceTests,ResolveSceneEntityManifestToolExecutorTests,SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests test`

Expected: PASS.

- [ ] **Step 3: Run frontend production build.**

Run: `cd ai-fusion-video-web && pnpm build`

Expected: PASS.
