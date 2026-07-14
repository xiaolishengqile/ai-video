# Asset Script Prebinding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pre-generation asset-to-script matching layer so user-uploaded images become the primary source for script parsing, scene binding, storyboard generation, and downstream reference images.

**Architecture:** Insert an explicit asset prebinding stage between asset upload and formal scene parsing. The backend builds an episode-scoped asset dictionary, performs deterministic matching first, optionally asks AI to choose among small candidate sets, stores the reviewed mapping, and makes later script/storyboard agents consume that mapping instead of creating unscoped no-image assets.

**Tech Stack:** Spring Boot Java backend, MyBatis Plus, Flyway migrations, AgentScope-based AI tools/prompts, Next.js/React frontend, Node tests and Maven tests.

## Global Constraints

- Uploaded user assets are the preferred source for script and storyboard binding.
- Matching is episode-scoped by default; do not silently bind assets across episodes.
- Do not use image embeddings or pixel-level visual recognition in the first version.
- Do not auto-create no-image assets during script parsing when a matching question can be surfaced as unmatched or ambiguous.
- Storyboard generation consumes scene bindings and asset snapshots; it must not re-guess from the full project asset list.
- Existing fields such as `character_asset_ids`, `scene_asset_id`, `prop_asset_ids`, `characterIds`, `sceneAssetItemId`, `sceneAssetItemIds`, and `propIds` remain for compatibility.

---

## 1. Target Product Flow

```text
User creates project
  -> User uploads assets by type and episode
  -> User uploads or imports script
  -> System runs asset-script prebinding
  -> User reviews unmatched and ambiguous bindings
  -> AI parses scenes using reviewed bindings
  -> AI generates storyboard from scene entityManifest and asset snapshots
  -> Frame/video agents use only bound AssetItems that have images
```

The prebinding step is a gate before formal scene parsing. It should be visible and repeatable, not hidden inside a long-running generation task.

## 2. Why This Changes the Current Behavior

The current system still has two competing behaviors:

- Newer episode-scoped flow: `search_episode_asset_candidates` and `resolve_scene_entity_manifest` bind current-episode assets into `entityManifest`.
- Older creation flow: agents or tools such as `list_project_assets`, `batch_create_assets`, or story-creation agents can still create no-image assets when names do not match.

The new prebinding layer makes "what user uploaded" explicit before parsing. Scene parsing then consumes a reviewed map instead of discovering assets from scratch.

## 3. Impact Analysis

### AI Script Parsing

AI script parsing must change.

Current desired behavior:

- `script_full_parse` should continue to save script metadata and episodes.
- Before `episode_scene_writer` parses scenes, the system should run prebinding for each `scriptEpisodeId`.
- `episode_scene_writer` should read the reviewed prebinding result and write `entityManifest` from that result.
- If a script entity has no reviewed match, it should be saved as `unmatched` or `needs_asset`, not silently converted into a new no-image asset.

What should be removed or restricted:

- Full script parsing must not call `list_project_assets` to perform broad matching.
- Formal scene parsing must not call `batch_create_assets`.
- Story creation flows that still use `episode_script_creator` need either the same prebinding capability or a separate note that they are "AI-created script mode" and may create placeholders.

### AI Storyboard Parsing

AI storyboard generation must change less than script parsing, but it must become stricter.

Current desired behavior:

- `script_to_storyboard` should keep creating an asset snapshot after scene binding and preprocessor completion.
- `episode_storyboard_writer` should only choose `AssetItem.id` values from the current episode snapshot and from each scene's `entityManifest` defaults.
- It should not call `list_project_assets`.
- It should not create missing main assets.
- It may create child variants only through the preprocessor, and those variants should be attached to already-bound uploaded assets.

The storyboard agent should treat the prebinding output as upstream truth. Its job is shot-level inclusion and exclusion, not entity identity matching.

### Asset Upload

Asset upload should not block on script availability. It should continue to save:

- `asset.id`
- `asset.type`
- `asset.episodeNumber`
- `asset.name`
- cleaned display/match names
- `assetItem.id`
- `assetItem.imageUrl`

After a script exists, these records become the asset dictionary used by prebinding.

### Frontend

Frontend needs a new "资产匹配检查" surface before AI parsing:

- 已自动匹配
- AI 建议匹配，待确认
- 剧本出现但无资产
- 上传但剧本未使用

The user can accept, change asset, mark as no-bind, or mark as missing asset.

## 4. Data Model

Create a persistent prebinding result table so long tasks do not pause waiting for user choices.

Recommended table: `afv_script_asset_binding`

Columns:

- `id`
- `project_id`
- `script_id`
- `script_episode_id`
- `episode_number`
- `script_scene_item_id` nullable for episode-level prebinding before scene records exist
- `asset_type` enum-like string: `character`, `scene`, `prop`
- `entity_name`
- `entity_key` nullable until scene parse creates stable keys
- `asset_id` nullable
- `asset_item_id` nullable
- `match_status`: `matched`, `suggested`, `ambiguous`, `unmatched`, `ignored`, `missing_asset`
- `match_source`: `exact_name`, `display_name`, `clean_name`, `alias`, `ai_selected`, `manual_selected`, `none`
- `confidence` integer 0-100
- `evidence_text` short excerpt from script or asset name
- `candidate_json` candidate list for ambiguous/suggested choices
- `reviewed` boolean
- `create_time`, `update_time`

Do not store image pixels or embeddings.

## 5. Matching Strategy

Use three layers:

```text
deterministic rules
  -> AI candidate choice
  -> manual confirmation
```

Deterministic rules auto-bind only when type, episode, and name evidence are strong:

- asset main name equals script entity name
- display name equals script entity name
- cleaned file name equals script entity name
- explicit alias equals script entity name

AI only receives small candidate sets from the same episode and same asset type. It should not see the full project asset list. Its output is a suggestion unless the confidence is high and there is a single candidate.

Manual confirmation happens before formal scene parsing continues in strict mode, or remains optional in loose mode.

## 6. Execution Phases

### Phase 1: Backend Dictionary and Rule Matching

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.2.4__script_asset_prebinding.sql`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/script/ScriptAssetBinding.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/mapper/script/ScriptAssetBindingMapper.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/ScriptAssetPrebindingService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/ScriptAssetPrebindingServiceTest.java`

**Interfaces:**
- Produces: `runEpisodePrebinding(Long projectId, Long scriptId, Long scriptEpisodeId): PrebindingSummary`
- Produces: `listBindings(Long scriptEpisodeId): List<ScriptAssetBinding>`

- [ ] **Step 1: Write failing tests for deterministic matching**

Test that same-episode exact names auto-match, other-episode assets do not match, and uploaded-but-unused assets are reported.

- [ ] **Step 2: Add migration and entity**

Create the `afv_script_asset_binding` table and map it with MyBatis Plus.

- [ ] **Step 3: Implement dictionary construction**

Load only assets with matching `projectId` and `episodeNumber`, include their first image-bearing `AssetItem`, and compute display/clean match names.

- [ ] **Step 4: Implement deterministic matching**

Scan the script episode raw text and existing scene text when available. Write `matched`, `unmatched`, and uploaded-unused rows.

- [ ] **Step 5: Verify Maven tests**

Run: `mvn -pl ai-fusion-video test -Dtest=ScriptAssetPrebindingServiceTest`

### Phase 2: AI Candidate Suggestion Tool

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/RunScriptAssetPrebindingToolExecutor.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ListScriptAssetBindingsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`

**Interfaces:**
- Tool: `run_script_asset_prebinding`
- Tool: `list_script_asset_bindings`

- [ ] **Step 1: Write failing tool tests**

Assert that the tool runs only for accessible projects and returns counts for `matched`, `suggested`, `ambiguous`, `unmatched`, and `uploadedUnused`.

- [ ] **Step 2: Register tools for script parsing agents**

Give `script_full_parse` access to run prebinding after saving episodes. Give `episode_scene_writer` access to read reviewed bindings.

- [ ] **Step 3: Update prompts**

Require `script_full_parse` to run prebinding per episode before dispatching scene parsing. Require `episode_scene_writer` to prefer reviewed bindings and not create assets for unmatched names.

- [ ] **Step 4: Verify agent registry tests or startup**

Run backend tests or application context startup check.

### Phase 3: Frontend Asset Matching Review

**Files:**
- Create: `ai-fusion-video-web/lib/api/script-asset-binding.ts`
- Create: `ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/_components/asset-binding-review.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/scripts/page.tsx`

**Interfaces:**
- `GET /api/script-asset-bindings?scriptEpisodeId=...`
- `POST /api/script-asset-bindings/run`
- `PUT /api/script-asset-bindings/{id}/review`

- [ ] **Step 1: Add API client**

Expose list, run, and review methods.

- [ ] **Step 2: Add review UI**

Show four groups: matched, suggested/ambiguous, script missing asset, uploaded unused.

- [ ] **Step 3: Add review actions**

Allow accept suggestion, choose a different same-episode asset, mark ignored, and mark missing asset.

- [ ] **Step 4: Gate parsing UX**

Before starting formal scene parsing, show summary counts. In strict mode, block when ambiguous rows remain; in loose mode, continue but do not bind unresolved rows.

- [ ] **Step 5: Verify frontend**

Run targeted lint, TypeScript, and build.

### Phase 4: Scene Manifest Integration

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/ResolveSceneEntityManifestToolExecutor.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`

**Interfaces:**
- `resolve_scene_entity_manifest` consumes reviewed binding IDs or `selectedAssetId`.
- `SceneEntity.source` includes `prebound_exact`, `prebound_ai_reviewed`, `prebound_manual`, `unmatched_prebinding`.

- [ ] **Step 1: Write failing manifest tests**

A reviewed binding should produce an `entityManifest` with the uploaded asset and image-bearing initial item. An unmatched binding should not create a new no-image asset.

- [ ] **Step 2: Implement reviewed binding lookup**

When scene entities are resolved, check the current episode binding table before creating or searching new candidates.

- [ ] **Step 3: Restrict auto-created placeholders**

Only allow placeholder creation in explicit fallback mode. Default mode should record unmatched.

- [ ] **Step 4: Verify scene parsing tests**

Run backend tests for scene manifest and save scene tool.

### Phase 5: Storyboard and Generation Integration

**Files:**
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-storyboard-writer.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-asset-preprocessor.system.md`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SaveStoryboardSceneShotsToolExecutor.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/GetStoryboardSceneItemsToolExecutor.java`

**Interfaces:**
- Storyboard snapshots contain only current-episode bound assets and valid child items.
- Frame/video generation receives only bound `AssetItem` records with images.

- [ ] **Step 1: Verify storyboard consumes scene bindings**

Add a regression test where scene `entityManifest` contains one uploaded character and one uploaded scene. Saved storyboard shots should inherit those item IDs.

- [ ] **Step 2: Restrict preprocessor**

Ensure the preprocessor only creates child variants under already-bound uploaded assets.

- [ ] **Step 3: Filter no-image references**

Make reference query results include `hasImage`, and make frame/video prompts skip no-image items.

- [ ] **Step 4: Verify storyboard flow**

Run backend tests plus a frontend build.

## 7. Rollout Mode

Use loose mode first:

- Automatically bind high-confidence deterministic matches.
- Surface AI suggestions and ambiguous rows before parsing.
- Allow continue with unresolved rows, but unresolved rows do not bind and do not create no-image assets.

After the UI is stable, add strict mode:

- Block scene parsing until all ambiguous rows are reviewed.

## 8. Acceptance Criteria

1. If the user uploaded `顾沉舟` as a character for episode 1, and episode 1 script mentions `顾沉舟`, scene parsing binds that uploaded asset and its image item.
2. If episode 2 has a same-name asset, episode 1 parsing does not use it.
3. If script mentions `老人` and the same episode has several plausible assets, the result is `ambiguous` or `suggested`, not silent auto-binding.
4. If script mentions an entity with no uploaded asset, no no-image system asset is created by default.
5. Uploaded but unused assets are visible in the review UI.
6. Storyboard generation inherits reviewed scene bindings and does not query the full project asset list for identity matching.
7. Frame/video reference generation uses only image-bearing `AssetItem` records.
8. Existing manually linked storyboard assets still render and save with the current compatibility fields.

## 9. Open Product Decisions

- Whether "AI suggested" rows require manual confirmation by default.
- Whether story-creation mode should also be forced through this prebinding flow, or remain a separate AI-created asset mode.
- Whether aliases should be persisted on `Asset.properties` in the first version or only inside binding review rows.
