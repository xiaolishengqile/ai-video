# Global Asset Selection and Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make global asset pagination stable, let “全选当前筛选结果” include unloaded matches, and rebuild the list correctly after deletion.

**Architecture:** Keep the existing `/api/asset/all` endpoint and infinite-scroll page. Add a stable secondary database sort, a tiny ID-based merge helper at the frontend boundary, and page handlers that fetch all matches only on explicit full selection and reload page one after deletion.

**Tech Stack:** Java 26, Spring Boot 3.5, MyBatis-Plus, JUnit 5/Mockito/AssertJ, Next.js 16, React 19, TypeScript, Node test runner, Tailwind CSS.

## Global Constraints

- Do not add an asset-ID-only API.
- Do not change project-scoped asset-page selection behavior.
- Do not add dependencies.
- Preserve current filter semantics for project, type, and keyword.
- Use `update_time DESC, id DESC` for stable asset pagination.

---

### Task 1: Stable backend pagination order

**Files:**
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java`

**Interfaces:**
- Consumes: `AssetService.pageAccessibleByUser(Long, Long, String, String, int, int)`.
- Produces: accessible and user-only asset wrappers ordered by update time descending and ID descending.

- [ ] **Step 1: Add a failing SQL-order assertion**

Capture the wrapper already passed to `assetMapper.selectPage` and add:

```java
assertThat(wrapperCaptor.getValue().getSqlSegment())
        .contains("ORDER BY update_time DESC,id DESC");
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `ai-fusion-video`:

```bash
./mvnw -Dtest=AssetServiceTests#pageAccessibleByUserUsesCurrentTeamScope test
```

Expected: FAIL because the SQL segment only orders by `update_time DESC`.

- [ ] **Step 3: Add the unique secondary order**

Update both query builders:

```java
.orderByDesc(Asset::getUpdateTime)
.orderByDesc(Asset::getId);
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Maven command. Expected: one test passes with zero failures.

- [ ] **Step 5: Commit the backend fix**

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java \
  ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceTests.java
git commit -m "fix(asset): stabilize global asset pagination"
```

### Task 2: Deduplicate assets at the frontend boundary

**Files:**
- Create: `ai-fusion-video-web/lib/asset-list.mjs`
- Create: `ai-fusion-video-web/lib/asset-list.test.mjs`

**Interfaces:**
- Produces: `mergeAssetsById(...assetGroups)` returning the first occurrence of each numeric asset ID while preserving input order.
- Consumed by: global asset first-page, load-more, and full-selection responses in Task 3.

- [ ] **Step 1: Write the failing merge test**

```js
import assert from "node:assert/strict";
import test from "node:test";

import { mergeAssetsById } from "./asset-list.mjs";

test("merges paginated assets once per id while preserving first-seen order", () => {
  const pageOne = [{ id: 1, name: "one" }, { id: 2, name: "original" }];
  const pageTwo = [{ id: 2, name: "duplicate" }, { id: 3, name: "three" }];

  assert.deepEqual(mergeAssetsById(pageOne, pageTwo), [pageOne[0], pageOne[1], pageTwo[1]]);
});
```

- [ ] **Step 2: Run the test and verify RED**

Run from `ai-fusion-video-web`:

```bash
node --test lib/asset-list.test.mjs
```

Expected: FAIL because `asset-list.mjs` does not exist.

- [ ] **Step 3: Implement the minimal merge helper**

```js
/**
 * @template {{ id: number }} T
 * @param {...T[]} assetGroups
 * @returns {T[]}
 */
export function mergeAssetsById(...assetGroups) {
  const unique = new Map();
  assetGroups.flat().forEach((asset) => {
    if (!unique.has(asset.id)) unique.set(asset.id, asset);
  });
  return [...unique.values()];
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run the same Node command. Expected: one test passes.

- [ ] **Step 5: Commit the helper**

```bash
git add ai-fusion-video-web/lib/asset-list.mjs ai-fusion-video-web/lib/asset-list.test.mjs
git commit -m "test(asset): cover paginated asset deduplication"
```

### Task 3: Select all filtered assets and refresh after deletion

**Files:**
- Create: `ai-fusion-video-web/app/(dashboard)/assets/global-asset-selection.test.cjs`
- Modify: `ai-fusion-video-web/app/(dashboard)/assets/page.tsx`

**Interfaces:**
- Consumes: `mergeAssetsById`, `assetApi.listAll`, `assetApi.deleteBatch`.
- Produces: `selectAllFiltered()` behavior, synchronous `loadingMoreRef` guard, page-one refresh after delete, and capped grid-card width.

- [ ] **Step 1: Write a failing page-wiring test**

```js
const assert = require("node:assert/strict");
const { readFile } = require("node:fs/promises");
const path = require("node:path");
const test = require("node:test");

test("global asset page selects every filtered result and reloads after deletion", async () => {
  const page = await readFile(path.join(__dirname, "page.tsx"), "utf8");

  assert.match(page, /mergeAssetsById/);
  assert.match(page, /loadingMoreRef/);
  assert.match(page, /const selectAllFiltered = async/);
  assert.match(page, /size: Math\.max\(total, PAGE_SIZE\)/);
  assert.match(page, /全选当前筛选结果/);
  assert.match(page, /await loadFirstPage\(\)/);
  assert.match(page, /max-w-\[320px\]/);
});
```

- [ ] **Step 2: Run the page test and verify RED**

Run from `ai-fusion-video-web`:

```bash
node --test 'app/(dashboard)/assets/global-asset-selection.test.cjs'
```

Expected: FAIL because the page has none of the new selection wiring.

- [ ] **Step 3: Add deduplication and the synchronous load-more guard**

Import `mergeAssetsById`, add `const loadingMoreRef = useRef(false)`, and change response application to:

```ts
setAssets(mergeAssetsById(resp.records || []));
```

and:

```ts
const loadMore = useCallback(async () => {
  if (loadingMoreRef.current || !hasMore) return;
  loadingMoreRef.current = true;
  setLoadingMore(true);
  try {
    const nextPage = currentPage + 1;
    const params: { projectId?: number; type?: string; keyword?: string; page: number; size: number } = {
      page: nextPage,
      size: PAGE_SIZE,
    };
    if (selectedProjectId !== "all") params.projectId = Number(selectedProjectId);
    if (selectedType !== "all") params.type = selectedType;
    if (debouncedKeyword.trim()) params.keyword = debouncedKeyword.trim();
    const resp = await assetApi.listAll(params);
    setAssets((previous) => mergeAssetsById(previous, resp.records || []));
    setTotal(resp.total);
    setCurrentPage(nextPage);
  } finally {
    loadingMoreRef.current = false;
    setLoadingMore(false);
  }
}, [hasMore, currentPage, selectedProjectId, selectedType, debouncedKeyword]);
```

- [ ] **Step 4: Implement full filtered selection**

Add `selectingAll` state and compute:

```ts
const allFilteredSelected = total > 0 && selectedIds.size === total;
```

Implement `selectAllFiltered` with the active filters:

```ts
const selectAllFiltered = async () => {
  if (allFilteredSelected) {
    setSelectedIds(new Set());
    return;
  }
  setSelectingAll(true);
  try {
    const params: { projectId?: number; type?: string; keyword?: string; page: number; size: number } = {
      page: 1,
      size: Math.max(total, PAGE_SIZE),
    };
    if (selectedProjectId !== "all") params.projectId = Number(selectedProjectId);
    if (selectedType !== "all") params.type = selectedType;
    if (debouncedKeyword.trim()) params.keyword = debouncedKeyword.trim();
    const response = await assetApi.listAll(params);
    const allAssets = mergeAssetsById(response.records || []);
    setAssets(allAssets);
    setTotal(response.total);
    setTypeCounts(response.typeCounts || {});
    setSelectedIds(new Set(allAssets.map((asset) => asset.id)));
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "全选资产失败");
  } finally {
    setSelectingAll(false);
  }
};
```

Use the button label `全选当前筛选结果`, show `选择中…` during the request, and disable selection/deletion controls while `selectingAll`.

- [ ] **Step 5: Refresh page one after successful deletion and cap card width**

Replace local array/count mutation after `deleteBatch` with:

```ts
setDeleteDialogOpen(false);
exitSelectionMode();
await loadFirstPage();
toast.success(`已删除 ${ids.length} 个资产`);
```

Add `w-full max-w-[320px]` to the grid card classes so a lone record cannot fill the content width.

- [ ] **Step 6: Run focused frontend tests and verify GREEN**

```bash
node --test lib/asset-list.test.mjs 'app/(dashboard)/assets/global-asset-selection.test.cjs'
```

Expected: two tests pass with zero failures.

- [ ] **Step 7: Run frontend static verification**

```bash
pnpm exec eslint 'app/(dashboard)/assets/page.tsx' lib/asset-list.mjs lib/asset-list.test.mjs 'app/(dashboard)/assets/global-asset-selection.test.cjs'
pnpm build
```

Expected: both commands exit 0.

- [ ] **Step 8: Run related backend tests**

From `ai-fusion-video`:

```bash
./mvnw -Dtest=AssetServiceTests,AssetServiceBulkDeleteTests test
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 9: Commit the page behavior**

```bash
git add 'ai-fusion-video-web/app/(dashboard)/assets/page.tsx' \
  'ai-fusion-video-web/app/(dashboard)/assets/global-asset-selection.test.cjs'
git commit -m "fix(asset): select and delete all filtered assets"
```
