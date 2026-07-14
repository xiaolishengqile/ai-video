# Project Asset Episode Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add episode buttons beneath the project asset type tabs so users can filter assets by episode or unscoped status.

**Architecture:** Keep the existing filtered asset request for cards, and add an unfiltered project asset request solely for the episode-button catalog. A small pure frontend helper derives the available episode numbers and filters the displayed list, keeping the UI behavior testable without backend changes.

**Tech Stack:** Next.js 16, React 19, TypeScript, Node.js built-in test runner, ESLint.

## Global Constraints

- Do not change the backend, database, or API contract.
- Use `Asset.episodeNumber`; `null` means an unscoped historical asset.
- Show only episode buttons present in the project's complete asset catalog, sorted numerically.
- Preserve the existing type and search request behavior; episode filtering happens after that request.

---

### Task 1: Isolate and test episode filter behavior

**Files:**
- Create: `ai-fusion-video-web/lib/asset-episode-filter.mjs`
- Create: `ai-fusion-video-web/lib/asset-episode-filter.test.mjs`

**Interfaces:**
- Produces: `listAssetEpisodes(assets)` returning unique ascending non-null episode numbers.
- Produces: `filterAssetsByEpisode(assets, activeEpisode)` where `undefined` returns every asset, `"unscoped"` returns only `episodeNumber === null`, and a number returns matching assets.

- [ ] **Step 1: Write the failing test**

```js
import assert from "node:assert/strict";
import test from "node:test";
import { filterAssetsByEpisode, listAssetEpisodes } from "./asset-episode-filter.mjs";

const assets = [
  { id: 1, episodeNumber: 10 },
  { id: 2, episodeNumber: null },
  { id: 3, episodeNumber: 2 },
  { id: 4, episodeNumber: 10 },
];

test("lists distinct episode numbers in ascending order", () => {
  assert.deepEqual(listAssetEpisodes(assets), [2, 10]);
});

test("filters all, a specific episode, and unscoped assets", () => {
  assert.deepEqual(filterAssetsByEpisode(assets, undefined).map(({ id }) => id), [1, 2, 3, 4]);
  assert.deepEqual(filterAssetsByEpisode(assets, 10).map(({ id }) => id), [1, 4]);
  assert.deepEqual(filterAssetsByEpisode(assets, "unscoped").map(({ id }) => id), [2]);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test lib/asset-episode-filter.test.mjs`

Expected: `ERR_MODULE_NOT_FOUND` for `asset-episode-filter.mjs`.

- [ ] **Step 3: Write minimal implementation**

```js
export function listAssetEpisodes(assets) {
  return [...new Set(assets.map(({ episodeNumber }) => episodeNumber).filter(Number.isInteger))]
    .sort((left, right) => left - right);
}

export function filterAssetsByEpisode(assets, activeEpisode) {
  if (activeEpisode === undefined) return assets;
  if (activeEpisode === "unscoped") return assets.filter(({ episodeNumber }) => episodeNumber === null);
  return assets.filter(({ episodeNumber }) => episodeNumber === activeEpisode);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test lib/asset-episode-filter.test.mjs`

Expected: 2 passing tests.

- [ ] **Step 5: Commit**

```bash
git add ai-fusion-video-web/lib/asset-episode-filter.mjs ai-fusion-video-web/lib/asset-episode-filter.test.mjs
git commit -m "test(asset): cover episode filter behavior"
```

### Task 2: Render and apply episode buttons in the asset library

**Files:**
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/assets/page.tsx:1-503`
- Modify: `ai-fusion-video-web/lib/asset-episode-filter.mjs` only if the component integration exposes an untested filter edge case.
- Test: `ai-fusion-video-web/lib/asset-episode-filter.test.mjs`

**Interfaces:**
- Consumes: `listAssetEpisodes(assets)` and `filterAssetsByEpisode(assets, activeEpisode)` from Task 1.
- Produces: type, search and episode-filtered `visibleAssets` for the grid, counter and bulk selection controls.

- [ ] **Step 1: Integrate the filter and controls**

Update the page with the following behavior:

```tsx
type EpisodeFilter = number | "unscoped" | undefined;

const [activeEpisode, setActiveEpisode] = useState<EpisodeFilter>(undefined);
const [catalogAssets, setCatalogAssets] = useState<Asset[]>([]);
const availableEpisodes = useMemo(() => listAssetEpisodes(catalogAssets), [catalogAssets]);
const visibleAssets = useMemo(
  () => filterAssetsByEpisode(assets, activeEpisode),
  [assets, activeEpisode]
);
```

In `fetchData`, load the current type/search result and `assetApi.list(projectId)` in parallel, assigning the second result to `catalogAssets`. Add a second horizontal button row under the existing type tags with `全部`, one `第 ${episode} 集` button per `availableEpisodes`, and `未分集`. Reset `selectedIds` when `activeEpisode` changes. Replace grid count, empty-state condition, card mapping, `selectedAssets`, and `allSelected` with `visibleAssets`. Task 1's tests remain the contract for the filtering behavior.

- [ ] **Step 2: Run focused verification**

Run: `node --test lib/*.test.mjs && pnpm exec eslint 'app/(dashboard)/projects/[id]/assets/page.tsx' lib/asset-episode-filter.mjs lib/asset-episode-filter.test.mjs && pnpm exec tsc --noEmit`

Expected: all Node tests pass; ESLint and TypeScript exit with status 0.

- [ ] **Step 3: Commit**

```bash
git add ai-fusion-video-web/app/'(dashboard)'/projects/'[id]'/assets/page.tsx ai-fusion-video-web/lib/asset-episode-filter.mjs ai-fusion-video-web/lib/asset-episode-filter.test.mjs
git commit -m "feat(asset): filter project assets by episode"
```

### Task 3: Production verification

**Files:**
- Verify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/assets/page.tsx`

- [ ] **Step 1: Build the web app**

Run: `pnpm build`

Expected: Next.js exits with status 0 and lists `/projects/[id]/assets` as a dynamic route.

- [ ] **Step 2: Review the final diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only the planned frontend files and their tests are modified or newly added.
