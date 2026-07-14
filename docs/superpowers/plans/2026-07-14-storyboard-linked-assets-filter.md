# Storyboard Linked Assets Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the storyboard linked-assets dialog default to the current shot episode and allow type plus episode filtering.

**Architecture:** Reuse the existing asset episode filter helper and keep all state in the existing storyboard page and dialog components. The page resolves the current shot's episode number; the dialog filters already-loaded assets locally.

**Tech Stack:** Next.js 16, React 19, TypeScript, Tailwind CSS, Node built-in test runner.

## Global Constraints

- Do not add dependencies.
- Do not change backend APIs.
- Preserve the existing linked-asset save payload.
- Preserve selected linked assets across filter switches.

---

### Task 1: Extend Asset Episode Filter

**Files:**
- Modify: `ai-fusion-video-web/lib/asset-episode-filter.mjs`
- Test: `ai-fusion-video-web/lib/asset-episode-filter.test.mjs`

**Interfaces:**
- Produces: `filterAssetsByEpisode(assets, activeEpisode, currentEpisodeNumber)` where `activeEpisode` is `number | "unscoped" | "other" | undefined` and `currentEpisodeNumber` is `number | null | undefined`.

- [ ] **Step 1: Write the failing test**

Add an assertion that `filterAssetsByEpisode(assets, "other", 10)` returns only assets whose `episodeNumber` is neither `10` nor `null`.

- [ ] **Step 2: Run the test**

Run: `node ai-fusion-video-web/lib/asset-episode-filter.test.mjs`
Expected: FAIL before implementation because `"other"` is not handled.

- [ ] **Step 3: Implement minimal helper change**

Update `filterAssetsByEpisode` so `"other"` excludes the current episode and excludes unscoped assets. If the current episode number is not an integer, return all assets.

- [ ] **Step 4: Verify helper tests**

Run: `node ai-fusion-video-web/lib/asset-episode-filter.test.mjs`
Expected: PASS.

### Task 2: Wire Current Episode Number Into Dialog

**Files:**
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/page.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/edit-assets-dialog.tsx`

**Interfaces:**
- Consumes: `StoryboardEpisode.episodeNumber`
- Produces: `currentEpisodeNumber?: number | null` prop on `EditItemAssetsDialog`

- [ ] **Step 1: Add storyboard episodes state**

In `page.tsx`, store `storyboardEpisodes` after `storyboardApi.listEpisodes(activeStoryboard.id)` in `loadStoryboard`.

- [ ] **Step 2: Add episode lookup**

Add a `useMemo` map from episode id to `episodeNumber`, and pass `editingItem?.storyboardEpisodeId` through it into the dialog.

- [ ] **Step 3: Add dialog prop**

Add `currentEpisodeNumber?: number | null` to `EditItemAssetsDialogProps`.

- [ ] **Step 4: Reset default filter on open**

When the dialog opens, set `activeEpisode` to the current episode number if it is an integer; otherwise set it to `undefined`.

### Task 3: Add Dialog Type and Episode Controls

**Files:**
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/edit-assets-dialog.tsx`

**Interfaces:**
- Consumes: `filterAssetsByEpisode`, `listAssetEpisodes`
- Produces: local `activeType` and `activeEpisode` state

- [ ] **Step 1: Import filter helpers**

Import `filterAssetsByEpisode` and `listAssetEpisodes` from `@/lib/asset-episode-filter.mjs`.

- [ ] **Step 2: Add tabs state**

Add `activeType` with values `undefined | "character" | "scene" | "prop"` and `activeEpisode` with values `undefined | "other" | "unscoped" | number`.

- [ ] **Step 3: Filter asset groups**

Filter `assetsList` first by episode scope, then by type for each section. Hide sections that do not match the selected type.

- [ ] **Step 4: Render controls**

Render type buttons `全部 / 角色 / 场景 / 道具` and episode buttons `全部 / 第 N 集 / 其他集 / 未分集` above the asset sections.

- [ ] **Step 5: Verify UI compile**

Run: `pnpm --dir ai-fusion-video-web lint`
Expected: No new lint errors from changed files.
