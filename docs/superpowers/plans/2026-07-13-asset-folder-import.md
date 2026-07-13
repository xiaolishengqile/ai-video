# Asset Folder Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a selected local image folder into one project asset type, creating independent assets or recognized child-asset variants and reporting every per-file result.

**Architecture:** Keep filename classification in a pure backend service, used by JSON preview and multipart persistence. The frontend previews server decisions, uploads roots before variants in chunks below the current 100MB request ceiling, and renders successes, skips and failures by original relative path.

**Tech Stack:** Spring Boot 3 / Java 21, MyBatis-Plus, MediaStorageService, Next.js / React / TypeScript, Axios multipart upload.

## Global Constraints

- Work directly on `main`; do not create a worktree.
- The user-selected `character`, `scene` or `prop` type applies to every file; never infer type from a folder or image.
- Remove the extension and only a leading `^[A-Za-z]+-\\d+` code prefix plus separators.
- Recognized child suffixes are exactly: `三视图`, `表情图`, `正面`, `侧面`, `背面`, `多机位`, `六面展开图`, `内景`, `外景`, `夜景`, `细节`, `战斗形态`, `战斗服`.
- A suffix is a child only when its normalized base exists as an incoming root or existing same-project/type asset. Otherwise create an independent asset; never fuzzy-match substrings.
- Root images must populate the auto-created `initial` AssetItem image URL; variants are additional AssetItems.
- Reuse PNG/JPEG/WebP/GIF and 100MB-per-file validation. Keep the 100MB multipart limit; the client chunks below 80MB.
- Each result has `relativePath`, original filename, `success`/`skipped`/`failed`; skipped/failed results include a human-readable reason. One failed file never rolls back successful siblings.
- Duplicate root assets skip. A recognized variant attaches to its existing parent but skips when that parent already has the same child-item name.
- No OCR, visual recognition, ZIP, content hashing or manual regrouping controls. Preserve `scripts/list-models.js`.

---

### Task 1: Deterministic filename classifier

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetFolderImportNameService.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/AssetFolderImportFile.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/AssetFolderImportPreviewItem.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportNameServiceTests.java`

**Interfaces:**
- `preview(List<AssetFolderImportFile> files)` returns an item per input: `relativePath`, `originalName`, `assetName`, nullable `variantName`, `itemType`, and `kind` (`root` or `variant`).

- [ ] **Step 1: Write failing tests.**

```java
@Test
void classifiesOnlyKnownSuffixesAgainstIncomingRoots() {
    List<AssetFolderImportPreviewItem> result = service.preview(List.of(
        file("A-15 地表实训发布厅.png"), file("A-15 地表实训发布厅多机位.png"),
        file("秦炽川.png"), file("秦炽川 三视图.png"), file("秦炽川便携核心诊断仪.png")));
    assertThat(result).extracting(AssetFolderImportPreviewItem::assetName)
        .containsExactly("地表实训发布厅", "地表实训发布厅", "秦炽川", "秦炽川", "秦炽川便携核心诊断仪");
    assertThat(result).extracting(AssetFolderImportPreviewItem::variantName)
        .containsExactly(null, "多机位", null, "三视图", null);
}
```

- [ ] **Step 2: Verify RED.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw -Dtest=AssetFolderImportNameServiceTests test`

Expected: compilation fails because classifier contracts do not exist.

- [ ] **Step 3: Implement minimal classification.**

```java
public List<AssetFolderImportPreviewItem> preview(List<AssetFolderImportFile> files) {
    Set<String> roots = files.stream().map(this::normalizedStem)
        .filter(stem -> splitKnownVariant(stem).variantName() == null).collect(toSet());
    return files.stream().map(file -> classify(file, roots)).toList();
}
```

Use longest-suffix-first matching. Map `正面`/`侧面`/`背面`/`细节`/`表情图` to existing AssetItem types and all other allowed suffixes to `variant`. Emit a root with the complete normalized name if no incoming root exists.

- [ ] **Step 4: Verify GREEN.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw -Dtest=AssetFolderImportNameServiceTests test && git diff --check`

Expected: pass with no whitespace errors.

- [ ] **Step 5: Commit.**

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetFolderImportNameService.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/AssetFolderImportFile.java ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/AssetFolderImportPreviewItem.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportNameServiceTests.java
git commit -m "feat(asset): classify folder import names"
```

### Task 2: Preview and resilient multipart import backend

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetFolderImportService.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/vo/AssetFolderImportPreviewReqVO.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/vo/AssetFolderImportResultVO.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/AssetController.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportServiceTests.java`

**Interfaces:**
- `preview(Long projectId, Long userId, String type, List<AssetFolderImportFile> files)` checks access/type and returns classifier decisions plus conflicts.
- `importFiles(Long projectId, Long userId, String type, List<MultipartFile> files, List<String> relativePaths)` returns `AssetFolderImportResultVO`; all processing-time errors are item results.
- `POST /api/asset/folder-import/preview` accepts `{projectId,type,files:[{relativePath,originalName}]}`.
- `POST /api/asset/folder-import` accepts multipart `projectId`, `type`, `files`, aligned `relativePaths`.

- [ ] **Step 1: Write failing import tests.**

```java
@Test
void importsRootImageIntoTheAutoCreatedInitialItem() {
    AssetFolderImportResultVO result = service.importFiles(1L, 9L, "scene",
        List.of(png("A-15 地表实训发布厅.png")), List.of("场景/A-15 地表实训发布厅.png"));
    assertThat(result.results()).singleElement().satisfies(item -> {
        assertThat(item.status()).isEqualTo("success");
        assertThat(item.assetName()).isEqualTo("地表实训发布厅");
    });
    verify(assetService).updateItem(argThat(item -> "initial".equals(item.getItemType()) && item.getImageUrl() != null));
}

@Test
void continuesAfterStorageFailureAndReturnsFailedPath() {
    when(mediaStorageService.storeBytes(any(), anyString(), anyString()))
        .thenThrow(new RuntimeException("storage unavailable")).thenReturn("/media/ok.png");
    AssetFolderImportResultVO result = service.importFiles(1L, 9L, "prop", twoFiles(), twoPaths());
    assertThat(result.results()).extracting(AssetFolderImportResultVO.Item::status)
        .containsExactly("failed", "success");
    assertThat(result.results().getFirst().relativePath()).isEqualTo(twoPaths().getFirst());
}
```

Also test denied access, root duplicate skip, existing-parent variant creation, duplicate variant skip, and `秦炽川便携核心诊断仪.png` as an independent root.

- [ ] **Step 2: Verify RED.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw -Dtest=AssetFolderImportServiceTests test`

Expected: compilation fails because import service and result contracts do not exist.

- [ ] **Step 3: Implement per-file import.**

```java
Asset asset = assetService.create(Asset.builder()
    .projectId(projectId).userId(userId).type(type).name(plan.assetName()).sourceType(1).build());
AssetItem initial = assetService.listItems(asset.getId()).stream()
    .filter(item -> "initial".equals(item.getItemType())).findFirst().orElseThrow();
assetService.updateItem(AssetItem.builder().id(initial.getId()).assetId(asset.getId())
    .itemType("initial").name(asset.getName()).imageUrl(url).sourceType(1).build());
```

For variants call `assetService.createItem` with the mapped item type, suffix name and stored URL. Validate access through `ProjectService.canAccessProject`, the three allowed types, aligned multipart metadata, image MIME type and 100MB size before storage. Catch exceptions inside the file loop and append `failed` with its original path/reason; do not call the existing single-file controller.

- [ ] **Step 4: Add endpoints and verify GREEN.**

Use `SecurityUtils.getCurrentUserId()` in the controller. Invalid request-level parameters return API errors, while processing-time individual failures stay in the successful response's result list.

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw -Dtest=AssetFolderImportNameServiceTests,AssetFolderImportServiceTests test`

Expected: all focused backend tests pass.

- [ ] **Step 5: Commit.**

```bash
git add ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetFolderImportService.java ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/vo/AssetFolderImportPreviewReqVO.java ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/vo/AssetFolderImportResultVO.java ai-fusion-video/src/main/java/com/stonewu/fusion/controller/asset/AssetController.java ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportServiceTests.java
git commit -m "feat(asset): import folder images in batches"
```

### Task 3: Folder selection, preview, chunked upload and result UI

**Files:**
- Create: `ai-fusion-video-web/components/dashboard/asset-folder-import-dialog.tsx`
- Modify: `ai-fusion-video-web/lib/api/asset.ts`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/assets/page.tsx`

**Interfaces:**
- `assetApi.previewFolderImport({projectId,type,files})` calls the JSON preview endpoint.
- `assetApi.importFolderChunk({projectId,type,files,relativePaths,onProgress})` uploads multipart and returns `AssetFolderImportResult`.
- `AssetFolderImportDialog` receives `projectId`, `onImported`, `onClose` and owns file selection, preview, upload and result display.

- [ ] **Step 1: Add failing TypeScript API usage.**

```ts
const preview = await assetApi.previewFolderImport({
  projectId: 1,
  type: "scene",
  files: [{ relativePath: "场景/A-15 地表实训发布厅.png", originalName: "A-15 地表实训发布厅.png" }],
});
const name: string = preview.items[0].assetName;
```

Put this real contract usage in the new dialog. The repository has no established frontend test runner, so TypeScript compilation is the RED/GREEN check.

- [ ] **Step 2: Verify RED.**

Run: `cd ai-fusion-video-web && pnpm exec tsc --noEmit`

Expected: it fails because folder-import API contracts and dialog do not exist.

- [ ] **Step 3: Implement UI and API contracts.**

Use a file input configured for folder selection in Chromium browsers, with `multiple` as fallback. Collect each `File` as `webkitRelativePath || file.name`; discard non-images before preview. Disable file selection until the user chooses type.

Render preview rows with original filename, target asset name, and `创建资产` or `添加子资产：<suffix>`. Permit removal from the selected list only; do not add manual regrouping. Use backend preview results, not duplicated filename parsing.

Upload root rows before variants. Build FormData chunks whose combined `File.size` stays below 80MB, then accumulate all per-file responses. Render final counts, skipped rows and a dedicated failure list containing relative path and reason. Call `onImported()` if any row succeeds so the assets grid reloads.

- [ ] **Step 4: Add page entry point and verify GREEN.**

Add `导入文件夹` beside `新建` in the project assets page; close/reload after import.

Run: `cd ai-fusion-video-web && pnpm exec tsc --noEmit`

Expected: pass.

Run: `cd ai-fusion-video-web && pnpm lint`

Expected: report existing unrelated lint errors if they remain; do not modify unrelated pages. New files must add no lint errors.

- [ ] **Step 5: Commit.**

```bash
git add ai-fusion-video-web/components/dashboard/asset-folder-import-dialog.tsx ai-fusion-video-web/lib/api/asset.ts ai-fusion-video-web/app/'(dashboard)'/projects/'[id]'/assets/page.tsx
git commit -m "feat(asset): add folder import preview"
```

### Task 4: Cross-layer regression verification

**Files:**
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportNameServiceTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportServiceTests.java`

**Interfaces:**
- A successful root import has a populated initial AssetItem and is therefore directly usable by the current storyboard binding flow.
- Every import input produces exactly one result with its original relative path.

- [ ] **Step 1: Add the final regression assertion.**

```java
@Test
void importedRootLeavesAnImageBackedInitialItemForStoryboardBinding() {
    AssetFolderImportResultVO result = service.importFiles(1L, 9L, "scene", oneRootFile(), oneRootPath());
    assertThat(result.results().getFirst().status()).isEqualTo("success");
    verify(assetService).updateItem(argThat(item -> "initial".equals(item.getItemType()) && item.getImageUrl() != null));
}
```

- [ ] **Step 2: Verify focused backend and frontend checks.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw -Dtest=AssetFolderImportNameServiceTests,AssetFolderImportServiceTests,SceneEntityManifestServiceTests,SaveStoryboardSceneShotsToolExecutorTests test`

Expected: all selected tests pass.

Run: `cd ai-fusion-video-web && pnpm exec tsc --noEmit`

Expected: pass.

- [ ] **Step 3: Record full regression status.**

Run: `cd ai-fusion-video && JAVA_HOME=/opt/homebrew/opt/openjdk@26 PATH=/opt/homebrew/opt/openjdk@26/bin:$PATH ./mvnw test`

Expected: the known model metadata resolver baseline failures may remain. Report actual counts/failure classes; do not claim full-suite success unless exit code is 0.

- [ ] **Step 4: Commit regression-only additions if any.**

```bash
git add ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetFolderImportServiceTests.java
git commit -m "test(asset): cover folder import storyboard readiness"
```

## Plan Self-Review

- Task 1 is the only filename classifier, so preview and persistence cannot drift.
- Task 2 updates the initial item created by `AssetService.create`, instead of leaving a cover-only root that AI storyboard cannot bind.
- Task 3 keeps each multipart request below the existing request limit and always shows failed image paths/reasons.
- Task 4 verifies imported assets remain usable by the existing storyboard flow without adding OCR, ZIP, hashing or manual regrouping.
