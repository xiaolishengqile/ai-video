# 场次实体清单与镜头资产继承 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 让剧本文字自动建立重要场次实体资产，并将其确定性继承到 AI 分镜镜头，避免群像、场景和道具关联缺失。

**Architecture:** 在 ScriptSceneItem 上保存 entityManifest JSON。新的解析工具负责资产去重、创建和初始子资产解析；分镜保存工具读取场次清单，将默认核心实体与镜头显式资产合并后再落库。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway、JUnit 5/Mockito、Next.js 16、TypeScript、pnpm。

## Global Constraints

- 仅使用剧本文字；不接入图片视觉识别、OCR 或人工确认。
- 资产主类型只有 character、scene、prop；群像使用 character + properties.entitySubtype=collective。
- 主动机甲归 character；载具、武器、静态残骸归 prop；群体残骸归 prop + collective。
- 每场至多自动创建 1 个场景、3 个角色/群像、3 个道具，按 core > supporting > atmospheric 筛选。
- 历史场次 entity_manifest 为空时保持旧行为，不回填。
- 创建失败、资产 ID 不存在或类型不符时返回错误，不保存半关联数据。

---

## File Structure

- Create: ai-fusion-video/src/main/resources/db/migration/V1.0.6.1.8__add_script_scene_entity_manifest.sql
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/model/SceneEntity.java
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/model/SceneEntityManifest.java
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/entity/script/ScriptSceneItem.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptSceneItemDetailQueryToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptEpisodeDetailQueryToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java
- Modify: ai-fusion-video/src/main/resources/prompts/agents/script-episode-upload.system.md
- Modify: ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md
- Modify: ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md
- Modify: ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard_episode-storyboard-writer.override.md
- Modify: ai-fusion-video-web/lib/api/script.ts
- Modify: ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx
- Modify: ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx
- Create: ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java
- Create: ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java
- Create: ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java

### Task 1: 持久化场次实体清单

**Files:**
- Create: ai-fusion-video/src/main/resources/db/migration/V1.0.6.1.8__add_script_scene_entity_manifest.sql
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/model/SceneEntity.java
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/model/SceneEntityManifest.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/entity/script/ScriptSceneItem.java
- Test: ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java

**Interfaces:**
- Produces SceneEntityManifest.fromJson(String) and toJson().
- Entity fields: key, name, assetType, entitySubtype, importance, defaultForShots, assetId, assetItemId, source.

- [ ] **Step 1: Write the failing JSON round-trip test.**

~~~
@Test
void manifestRoundTripKeepsCollectiveAndResolvedIds() {
    SceneEntity entity = new SceneEntity("character:evacuees", "撤离士兵群",
            "character", "collective", "core", true, 11L, 21L, "auto_created");
    SceneEntityManifest parsed = SceneEntityManifest.fromJson(
            new SceneEntityManifest(1, List.of(entity)).toJson());
    assertThat(parsed.entities()).containsExactly(entity);
}
~~~

- [ ] **Step 2: Run it and verify it fails.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SceneEntityManifestServiceTests test

Expected: compilation failure because the two model classes do not exist.

- [ ] **Step 3: Implement the model and migration.**

~~~
ALTER TABLE afv_script_scene_item
  ADD COLUMN entity_manifest json NULL COMMENT '场次实体清单（含已解析资产ID）'
  AFTER prop_asset_ids;
~~~

Use a record-style model; blank JSON produces version 1 with an empty list. Add entityManifest with the existing JsonbTypeHandler to ScriptSceneItem.

- [ ] **Step 4: Re-run the focused test.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SceneEntityManifestServiceTests test

Expected: PASS.

- [ ] **Step 5: Commit.**

~~~
git add ai-fusion-video/src/main/resources/db/migration/V1.0.6.1.8__add_script_scene_entity_manifest.sql ai-fusion-video/src/main/java/com/stonewu/fusion/entity/script/ScriptSceneItem.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/model ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java
git commit -m "feat(script): add scene entity manifest model"
~~~

### Task 2: 解析清单并自动创建/复用资产

**Files:**
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java
- Create: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java
- Test: ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java

**Interfaces:**
- Consumes resolve(Long projectId, Long userId, SceneEntityManifest requested).
- Produces a manifest where each core/supporting entity has Asset.id and initial AssetItem.id.
- Tool input: projectId and entities. Tool output: entityManifest, createdCount, reusedCount, filteredCount.

- [ ] **Step 1: Write failing reuse and limit tests.**

~~~
@Test
void resolveCreatesCoreCollectiveAndReusesExistingTrain() {
    SceneEntityManifest result = service.resolve(1L, 9L, manifestWithCoreCrowdAndTrain());
    assertThat(result.entities()).allMatch(e -> e.assetId() != null && e.assetItemId() != null);
    verify(assetService).findByProjectTypeAndName(1L, "prop", "装甲撤离列车");
}

@Test
void resolveDropsFourthSupportingPropInsteadOfCreatingIt() {
    assertThat(service.resolve(1L, 9L, manifestWithFourSupportingProps()).entities())
            .filteredOn(e -> "atmospheric".equals(e.importance())).hasSize(1);
}
~~~

- [ ] **Step 2: Run tests and verify they fail.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SceneEntityManifestServiceTests test

Expected: compilation failure for SceneEntityManifestService.

- [ ] **Step 3: Implement the resolver.**

Validate the three allowed types, entity subtypes, importance and per-scene limits. For core/supporting entries call AssetService.findByProjectTypeAndName; create missing Asset with sourceType=2; call AssetService.listItems and use/create the initial item; return resolved IDs. Preserve atmospheric entries but clear their IDs. Register the executor only on script parsing agents.

- [ ] **Step 4: Run focused tests.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SceneEntityManifestServiceTests test

Expected: PASS, including reuse, initial-item creation and limits.

- [ ] **Step 5: Commit.**

~~~
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java
git commit -m "feat(script): resolve scene entities into assets"
~~~

### Task 3: 场次保存与分镜查询接入

**Files:**
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptSceneItemDetailQueryToolExecutor.java
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptEpisodeDetailQueryToolExecutor.java
- Modify: ai-fusion-video/src/main/resources/prompts/agents/script-episode-upload.system.md
- Modify: ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md
- Test: ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java

**Interfaces:**
- save_script_scene_items scenes additionally consume entity_manifest.
- get_script_scene produces entityManifest and defaultCharacterAssetItemIds/defaultSceneAssetItemId/defaultPropAssetItemIds.

- [ ] **Step 1: Write failing save/query tests.**

~~~
@Test
void saveRejectsManifestIdsThatDisagreeWithSceneAssetIds() {
    assertThat(executor.execute(sceneWithConflictingManifest(), context))
            .contains("entity_manifest 与场次资产关联不一致");
}

@Test
void sceneDetailReturnsResolvedDefaultAssetItemIds() {
    assertThat(JSONUtil.parseObj(detailExecutor.execute("{\"scriptSceneItemId\":1}", context))
            .getLong("defaultSceneAssetItemId")).isEqualTo(500L);
}
~~~

- [ ] **Step 2: Run and verify failure.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SaveScriptSceneItemsToolExecutorTests test

Expected: response and validation are absent.

- [ ] **Step 3: Implement persistence and outputs.**

SaveScriptSceneItemsToolExecutor parses entity_manifest, rejects unresolved non-atmospheric entities, derives characterAssetIds/sceneAssetId/propAssetIds from it, and rejects a conflicting legacy field. Detail and full episode queries return the manifest and derived initial AssetItem IDs. Update both parsing prompts to call query_asset_metadata, produce a manifest, resolve it, then save scenes.

- [ ] **Step 4: Run regression tests.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SaveScriptSceneItemsToolExecutorTests,SceneEntityManifestServiceTests test

Expected: PASS.

- [ ] **Step 5: Commit.**

~~~
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptSceneItemDetailQueryToolExecutor.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ScriptEpisodeDetailQueryToolExecutor.java ai-fusion-video/src/main/resources/prompts/agents/script-episode-upload.system.md ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveScriptSceneItemsToolExecutorTests.java
git commit -m "feat(script): persist resolved scene entity manifests"
~~~

### Task 4: 保存分镜时确定性继承场次资产

**Files:**
- Modify: ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java
- Modify: ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md
- Modify: ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard_episode-storyboard-writer.override.md
- Test: ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java

**Interfaces:**
- save_storyboard_scene_shots consumes required scriptSceneItemId and optional per-shot excludedDefaultEntityKeys.
- Output adds assetBindingSources with inherited, explicit and excluded AssetItem IDs.

- [ ] **Step 1: Write failing inheritance tests.**

~~~
@Test
void saveInheritsCoreSceneCrowdAndTrainWhenShotOmitsAssetIds() {
    executor.execute(requestForScene(1L).withShot("裂缝映出撤离站台"), context);
    StoryboardItem saved = capturedItems().getFirst();
    assertThat(saved.getSceneAssetItemId()).isEqualTo(501L);
    assertThat(saved.getCharacterIds()).isEqualTo("[502]");
    assertThat(saved.getPropIds()).isEqualTo("[503]");
}

@Test
void saveAllowsCloseUpToExcludeCrowdButNotOnlyCoreScene() {
    assertThat(executor.execute(requestExcluding("character:evacuees", "close_up"), context))
            .contains("success");
    assertThat(executor.execute(requestExcluding("scene:station", "close_up"), context))
            .contains("不能排除该场唯一核心场景");
}
~~~

- [ ] **Step 2: Run and verify failure.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SaveStoryboardSceneShotsToolExecutorTests test

Expected: the current tool saves null associations.

- [ ] **Step 3: Implement merge and validation.**

Inject ScriptSceneItemMapper and SceneEntityManifestService. Verify scriptSceneItemId belongs to the storyboard episode's script episode. Merge core defaults and explicit IDs; only merge supporting entries when explicitly provided by the Agent; apply only exclusion reasons offscreen, close_up or not_yet_appeared; deduplicate IDs. Reject invalid AssetItem IDs, type mismatches and removal of the sole core scene before constructing StoryboardItem.

- [ ] **Step 4: Update prompts and run focused tests.**

Require get_script_scene defaults to be used. Core defaults are implicit and exclusions must include an exact entity key plus a permitted reason.

Run: cd ai-fusion-video && ./mvnw -Dtest=SaveStoryboardSceneShotsToolExecutorTests,SceneEntityManifestServiceTests test

Expected: PASS.

- [ ] **Step 5: Commit.**

~~~
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard_episode-storyboard-writer.override.md ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java
git commit -m "feat(storyboard): inherit scene entity assets per shot"
~~~

### Task 5: 只读展示、统计与全量验证

**Files:**
- Modify: ai-fusion-video-web/lib/api/script.ts
- Modify: ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/scene-detail.tsx
- Modify: ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx
- Test: ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java

**Interfaces:**
- Script scene client type exposes entityManifest.
- Scene detail displays type, subtype, importance and source.
- Storyboard binding display distinguishes inherited, AI explicit and AI excluded.

- [ ] **Step 1: Write the failing binding-source test.**

~~~
@Test
void saveResponseMarksInheritedAndExplicitBindings() {
    JSONObject result = JSONUtil.parseObj(executor.execute(requestWithExplicitProp(), context));
    assertThat(result.getJSONArray("assetBindingSources").getJSONObject(0).getJSONArray("inherited"))
            .contains(501L, 502L);
    assertThat(result.getJSONArray("assetBindingSources").getJSONObject(0).getJSONArray("explicit"))
            .contains(599L);
}
~~~

- [ ] **Step 2: Run and verify failure.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SaveStoryboardSceneShotsToolExecutorTests test

Expected: assetBindingSources is absent.

- [ ] **Step 3: Implement returned metadata and read-only UI.**

Store binding source in StoryboardItem.customData and return it from the save tool. Add TypeScript manifest types. In scene-detail render “场次实体” badges for 群像/机甲/载具, 核心/辅助 and 自动创建/复用. In storyboard-ref-panel render “关联来源：继承 / AI 添加 / AI 排除”. Do not add edit or confirmation controls.

- [ ] **Step 4: Run focused backend checks and frontend lint.**

Run: cd ai-fusion-video && ./mvnw -Dtest=SceneEntityManifestServiceTests,SaveScriptSceneItemsToolExecutorTests,SaveStoryboardSceneShotsToolExecutorTests test

Expected: PASS.

Run: cd ai-fusion-video-web && pnpm lint

Expected: exit code 0.

- [ ] **Step 5: Run full regression and commit.**

Run: cd ai-fusion-video && ./mvnw test

Expected: exit code 0.

~~~
git add ai-fusion-video-web/lib/api/script.ts ai-fusion-video-web/app/'(dashboard)'/projects/'[id]'/scripts/_components/scene-detail.tsx ai-fusion-video-web/app/'(dashboard)'/projects/'[id]'/storyboards/_components/storyboard-ref-panel.tsx ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutorTests.java
git commit -m "feat(storyboard): show scene entity asset bindings"
~~~

## Plan Self-Review

- Tasks 1–3 cover persisted manifests and automatic asset creation.
- Task 4 covers default inheritance and all validation rules.
- Task 5 covers task-facing visibility, UI, focused tests, lint and full regression.
- The plan deliberately excludes vision, OCR, user confirmation, a new asset type and historical backfill.
