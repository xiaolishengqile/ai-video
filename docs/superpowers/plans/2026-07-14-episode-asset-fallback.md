# 分集缺失资产自动补建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让剧本中缺失但必要的角色、场景、道具自动补建为当前集的无图资产，并关联到场次与分镜。

**Architecture:** 保持现有 `SceneEntityManifestService` 作为唯一解析入口：先查本集资产，未命中时调用现有 `AssetService.findOrCreate` 创建 `episodeNumber` 已绑定的资产与初始子资产。场次保存接受成对为空的未匹配 ID，但自动补建结果带完整 ID；分镜复用现有同集验证。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5 / Mockito、Next.js、TypeScript。

## Global Constraints

- 不生成图片、不增加图像模型调用或依赖。
- 只补建 `core`、`supporting` 且处于当前实体数量限制内的项目；氛围和超限实体不补建。
- 补建资产必须带当前 `episodeNumber`，不同集同名保持独立。
- 补建资产的 source 为 `auto_created_episode_catalog`；资产本体使用 `sourceType=2`。
- 所有生产行为先有失败测试，再写最小实现。

---

### Task 1: 按需补建当前集缺失实体

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java`

**Interfaces:**
- `SceneEntityManifestService#resolve(Long projectId, Long userId, Integer episodeNumber, SceneEntityManifest requested)` 保持签名。
- 缺失保留实体返回 `source="auto_created_episode_catalog"`、当前集 Asset 和 `initial` AssetItem ID。

- [ ] **Step 1: 写入失败测试。**

```java
@Test
void resolveCreatesAPlaceholderOnlyInTheCurrentEpisodeWhenAssetIsMissing() {
    Asset created = Asset.builder().id(10L).projectId(1L).episodeNumber(2).name("能量核心").build();
    when(assetService.findByProjectEpisodeTypeAndName(1L, 2, "prop", "能量核心")).thenReturn(null);
    when(assetService.findOrCreate(any(Asset.class)))
            .thenReturn(new AssetService.FindOrCreateResult(created, true));
    when(assetService.listItems(10L)).thenReturn(List.of(AssetItem.builder().id(11L).itemType("initial").build()));

    SceneEntity entity = service.resolve(1L, 9L, 2,
            new SceneEntityManifest(1, List.of(entity("prop:core", "能量核心", "prop", "device", "core"))))
            .entities().getFirst();

    assertThat(entity).extracting(SceneEntity::assetId, SceneEntity::assetItemId, SceneEntity::source)
            .containsExactly(10L, 11L, "auto_created_episode_catalog");
    verify(assetService).findOrCreate(argThat(asset -> asset.getEpisodeNumber().equals(2)
            && asset.getSourceType().equals(2)));
}
```

- [ ] **Step 2: 验证失败。**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home mvn -Dtest=SceneEntityManifestServiceTests test`

Expected: FAIL，因为当前缺失资产会返回 `unmatched_episode_catalog`。

- [ ] **Step 3: 实现最小补建分支。**

```java
AssetService.FindOrCreateResult result = assetService.findOrCreate(Asset.builder()
        .projectId(projectId).episodeNumber(episodeNumber).userId(userId)
        .type(entity.assetType()).name(entity.name()).sourceType(2).build());
AssetItem initial = assetService.listItems(result.asset().getId()).stream()
        .filter(item -> "initial".equals(item.getItemType())).findFirst()
        .orElseThrow(() -> new IllegalStateException("补建资产缺少初始子资产"));
return withIds(entity, entity.importance(), result.asset().getId(), initial.getId(),
        result.created() ? "auto_created_episode_catalog" : "matched");
```

仅替换非氛围、未超限、精确查询未命中的分支；保留命中资产的 `matched` 和 `filtered_limit` / `atmospheric` 逻辑。

- [ ] **Step 4: 验证通过。**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home mvn -Dtest=SceneEntityManifestServiceTests test`

Expected: PASS。

- [ ] **Step 5: 提交。**

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java
git commit -m "feat(script): auto-create missing episode assets"
```

### Task 2: 暴露补建来源并回归场次到分镜链路

**Files:**
- Modify: `ai-fusion-video-web/lib/api/script.ts`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java`

**Interfaces:**
- `SceneEntitySource` 包含 `auto_created_episode_catalog`。
- 自动补建实体写入 `entity_manifest` 后，其 asset item 可以被 `save_storyboard_scene_shots` 作为本集默认资产继承。

- [ ] **Step 1: 写入失败的保存链路测试。**

```java
@Test
void savePersistsAnAutoCreatedEpisodeEntityAsTheDefaultStoryboardAsset() {
    String manifest = new SceneEntityManifest(1, List.of(
            new SceneEntity("prop:core", "能量核心", "prop", "device", "core", true,
                    102L, 502L, "auto_created_episode_catalog"))).toJson();
    // 保存场次后，分镜镜头省略 propIds 仍应保存 [502]；现有同集 stub 负责验证 episodeNumber。
}
```

- [ ] **Step 2: 验证失败。**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home mvn -Dtest=SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests test`

Expected: FAIL 或 TypeScript 类型不包含新的 source。

- [ ] **Step 3: 实现来源展示与回归断言。**

```ts
export type SceneEntitySource =
  | "auto_created"
  | "auto_created_episode_catalog"
  | "matched"
  | "unmatched_episode_catalog"
  | "reused" | "atmospheric" | "filtered_limit";
```

在 `entitySourceLabels` 加入 `auto_created_episode_catalog: "AI 补建"`。不改分镜保存服务：其现有 manifest 默认继承与集号校验已满足该链路；测试证明自动补建的本集 item 可直接继承。

- [ ] **Step 4: 验证通过。**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home mvn -Dtest=SceneEntityManifestServiceTests,SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests test`

Expected: PASS。

- [ ] **Step 5: 提交。**

```bash
git add ai-fusion-video-web/lib/api/script.ts 'ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx' ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java
git commit -m "feat(ui): label auto-created episode assets"
```

### Task 3: 完整验证

- [ ] **Step 1: 执行相关后端回归。**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home mvn -Dtest=AssetServiceTests,AssetFolderImportServiceTests,AssetCatalogSnapshotServiceTests,SceneEntityManifestServiceTests,ResolveSceneEntityManifestToolExecutorTests,SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests test`

Expected: PASS。

- [ ] **Step 2: 检查变更完整性。**

Run: `git diff --check 7a53eb0..HEAD && git status --short`

Expected: 无空白错误，工作树干净。
