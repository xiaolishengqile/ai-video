# Asset Bulk Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users select currently loaded assets and safely delete the selected set in one action.

**Architecture:** Add an access-checked transactional `deleteAccessible` service method used by both single and batch controller endpoints. Add selection-mode state to the global asset page, and call the new batch endpoint only after a destructive confirmation dialog.

**Tech Stack:** Spring Boot, MyBatis-Plus, JUnit 5/Mockito, Next.js, React, TypeScript, existing Base UI dialog/checkbox components.

## Global Constraints

- Do not add dependencies.
- “全选” only selects currently loaded assets.
- Any inaccessible asset rejects the entire deletion request before deletion begins.
- Keep logical deletion and the existing single-delete interaction.

---

### Task 1: Access-checked backend deletion

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/AssetController.java`
- Create: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceBulkDeleteTests.java`

**Interfaces:**
- Produces: `void deleteAccessible(List<Long> ids, Long userId)`.
- Produces: `DELETE /api/asset/batch` accepting `{ "ids": [1, 2] }`.

- [x] **Step 1: Write failing service tests**

```java
assertThatCode(() -> assetService.deleteAccessible(List.of(1L, 2L), 9L)).doesNotThrowAnyException();
verify(assetMapper).deleteById(1L);
verify(assetMapper).deleteById(2L);

assertThatThrownBy(() -> assetService.deleteAccessible(List.of(1L, 2L), 9L))
        .hasMessageContaining("无权删除资产");
verify(assetMapper, never()).deleteById(anyLong());
```

- [x] **Step 2: Run the tests and verify RED**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceBulkDeleteTests test`

Expected: compilation failure because `deleteAccessible` does not exist.

- [x] **Step 3: Implement the minimal service and controller code**

```java
@Transactional
public void deleteAccessible(List<Long> ids, Long userId) {
    List<Asset> assets = ids.stream().distinct().map(this::getById).toList();
    if (assets.stream().anyMatch(asset -> !canAccessAsset(asset, userId))) {
        throw new BusinessException(403, "无权删除资产");
    }
    assets.forEach(asset -> delete(asset.getId()));
}
```

Add `@DeleteMapping("/batch")` before `@DeleteMapping("/{id}")`, accept `Map<String, List<Long>>`, and route both endpoints through `deleteAccessible`.

- [x] **Step 4: Run the tests and verify GREEN**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceBulkDeleteTests,AssetServiceTests test`

Expected: `BUILD SUCCESS`.

### Task 2: Asset-page selection and deletion UI

**Files:**
- Modify: `ai-fusion-video-web/lib/api/asset.ts`
- Modify: `ai-fusion-video-web/app/(dashboard)/assets/page.tsx`

**Interfaces:**
- Produces: `assetApi.deleteBatch(ids: number[]): Promise<boolean>`.
- Consumes: the existing `Dialog`, `Checkbox`, `Button`, and toast pattern.

- [x] **Step 1: Add a focused UI behavior test or type-level failing assertion**

Add a test or compile-time use of `assetApi.deleteBatch([1, 2])`; the check must fail until the API method exists.

- [x] **Step 2: Run the focused frontend check and verify RED**

Run: `pnpm exec tsc --noEmit`

Expected: TypeScript error that `deleteBatch` does not exist.

- [x] **Step 3: Implement the smallest selection flow**

```tsx
const [selectionMode, setSelectionMode] = useState(false);
const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
const selectedCount = selectedIds.size;
```

Render checkboxes only in selection mode. Add controls for entering/exiting selection mode, selecting all currently loaded asset IDs, and opening a destructive confirmation dialog. On success, remove selected assets from state, decrement total/type counts by the removed assets, then clear selection.

- [x] **Step 4: Run the frontend check and verify GREEN**

Run: `pnpm exec tsc --noEmit && pnpm build`

Expected: both commands exit successfully.

### Task 3: Final verification

**Files:**
- Verify only.

- [x] **Step 1: Run backend and frontend checks**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=AssetServiceBulkDeleteTests,AssetServiceTests test && cd ../ai-fusion-video-web && pnpm exec tsc --noEmit && pnpm build && cd .. && git diff --check`

Expected: all commands succeed.

- [ ] **Step 2: Commit implementation**

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetService.java \
  ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/AssetController.java \
  ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetServiceBulkDeleteTests.java \
  ai-fusion-video-web/lib/api/asset.ts \
  'ai-fusion-video-web/app/(dashboard)/assets/page.tsx' \
  docs/superpowers/plans/2026-07-14-asset-bulk-delete-implementation.md
git commit -m "feat: add bulk asset deletion"
```
