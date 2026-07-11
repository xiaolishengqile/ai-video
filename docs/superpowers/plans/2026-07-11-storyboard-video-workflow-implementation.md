# Storyboard Video Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first phase of the storyboard video workflow redesign: per-shot workflow modes, mode-specific storyboard assets, optional first/last frames, mode-aware prompt generation hooks, and Excel export.

**Architecture:** Store the new workflow state on `afv_storyboard_item`, expose it through the existing storyboard item APIs, add focused AI tools/prompts for mode classification and workflow asset persistence, and keep the frontend table/details UI as the primary control surface. Excel export is generated server-side with Hutool and downloaded from the storyboard page.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis Plus, Flyway, Hutool Excel, Next.js 16, React 19, TypeScript, lucide-react.

## Global Constraints

- Do not remove the existing first-frame/last-frame feature.
- Do not require first-frame/last-frame images before video generation.
- Do not generate 25-grid images for action mode.
- Use Excel as the first export format; do not implement PDF in this phase.
- Preserve existing storyboard generation behavior and extend only the downstream video-material workflow.
- Media fields in Excel should export as links, not embedded images, in the first phase.

---

### Task 1: Persist Storyboard Workflow Fields

**Files:**
- Create: `ai-fusion-video/src/main/resources/db/migration/V1.0.6.1.5__storyboard_video_workflow.sql`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/entity/storyboard/StoryboardItem.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/storyboard/vo/StoryboardItemCreateReqVO.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/storyboard/vo/StoryboardItemUpdateReqVO.java`
- Modify: `ai-fusion-video-web/lib/api/storyboard.ts`

**Interfaces:**
- Produces backend fields:
  - `videoWorkflowMode: String`
  - `videoWorkflowResolvedMode: String`
  - `videoWorkflowReason: String`
  - `storyboardImageUrl: String`
  - `grid25ImageUrl: String`
  - `grid25Prompt: String`
  - `actionStoryboardImageUrl: String`
  - `actionStoryboardPrompt: String`
  - `motionPlan: String`
  - `keyFrameImageUrls: String`
  - `videoPromptMode: String`
  - `qualityCheckStatus: Integer`
  - `qualityCheckResult: String`
- Produces frontend types:
  - `StoryboardVideoWorkflowMode = "auto" | "narrative" | "action"`
  - matching nullable fields on `StoryboardItem`, `StoryboardItemCreateReq`, and `StoryboardItemUpdateReq`

- [ ] **Step 1: Add Flyway migration**

```sql
ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `video_workflow_mode` varchar(32) NOT NULL DEFAULT 'auto' COMMENT '视频工作流模式：auto-自动 narrative-剧情 action-战斗' AFTER `video_prompt`,
    ADD COLUMN `video_workflow_resolved_mode` varchar(32) DEFAULT NULL COMMENT '自动判断后的实际视频工作流模式' AFTER `video_workflow_mode`,
    ADD COLUMN `video_workflow_reason` text DEFAULT NULL COMMENT '视频工作流模式判断原因' AFTER `video_workflow_resolved_mode`,
    ADD COLUMN `storyboard_image_url` varchar(1024) DEFAULT NULL COMMENT '故事板图URL' AFTER `video_workflow_reason`,
    ADD COLUMN `grid25_image_url` varchar(1024) DEFAULT NULL COMMENT '25宫格剧情故事板图URL' AFTER `storyboard_image_url`,
    ADD COLUMN `grid25_prompt` text DEFAULT NULL COMMENT '25宫格剧情故事板提示词' AFTER `grid25_image_url`,
    ADD COLUMN `action_storyboard_image_url` varchar(1024) DEFAULT NULL COMMENT '动作故事板图URL' AFTER `grid25_prompt`,
    ADD COLUMN `action_storyboard_prompt` text DEFAULT NULL COMMENT '动作故事板提示词' AFTER `action_storyboard_image_url`,
    ADD COLUMN `motion_plan` text DEFAULT NULL COMMENT '战斗身位调度与动作规划' AFTER `action_storyboard_prompt`,
    ADD COLUMN `key_frame_image_urls` json DEFAULT NULL COMMENT '关键帧URL数组' AFTER `motion_plan`,
    ADD COLUMN `video_prompt_mode` varchar(32) DEFAULT NULL COMMENT '视频提示词生成模式' AFTER `key_frame_image_urls`,
    ADD COLUMN `quality_check_status` tinyint DEFAULT 0 COMMENT '质检状态：0未质检 1质检中 2通过 3失败' AFTER `video_prompt_mode`,
    ADD COLUMN `quality_check_result` text DEFAULT NULL COMMENT '质检结果' AFTER `quality_check_status`;
```

- [ ] **Step 2: Add Java entity fields**

Add fields after `videoPrompt` in `StoryboardItem` using the exact names listed in Interfaces. Mark `keyFrameImageUrls` with `@TableField(typeHandler = JsonbTypeHandler.class)`.

- [ ] **Step 3: Add request VO fields**

Add the same fields to `StoryboardItemCreateReqVO` and `StoryboardItemUpdateReqVO`. MapStruct will pass them through by matching names.

- [ ] **Step 4: Add TypeScript API fields**

In `ai-fusion-video-web/lib/api/storyboard.ts`, export:

```ts
export type StoryboardVideoWorkflowMode = "auto" | "narrative" | "action";
export type StoryboardQualityCheckStatus = 0 | 1 | 2 | 3;
```

Add the new nullable fields to `StoryboardItem`, `StoryboardItemCreateReq`, and `StoryboardItemUpdateReq`.

- [ ] **Step 5: Verify backend compile**

Run: `cd ai-fusion-video && ./mvnw -q -DskipTests compile`

Expected: command exits 0.

---

### Task 2: Add Workflow Update and Excel Export APIs

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/storyboard/vo/StoryboardWorkflowUpdateReqVO.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/storyboard/vo/StoryboardExcelExportReqVO.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/storyboard/StoryboardExcelExportService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/controller/storyboard/StoryboardController.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/storyboard/StoryboardService.java`
- Modify: `ai-fusion-video-web/lib/api/storyboard.ts`

**Interfaces:**
- `StoryboardService.updateItemWorkflow(Long itemId, StoryboardWorkflowUpdateReqVO reqVO): StoryboardItem`
- `StoryboardExcelExportService.export(Long storyboardId, Long episodeId, Long sceneId): byte[]`
- `PUT /api/storyboard/item/{id}/workflow`
- `GET /api/storyboard/{storyboardId}/export/excel?episodeId=&sceneId=`

- [ ] **Step 1: Add update request VO**

Create `StoryboardWorkflowUpdateReqVO` with nullable fields for mode, resolved mode, reason, storyboard image, 25-grid image/prompt, action storyboard image/prompt, motion plan, key frames, prompt mode, quality status, and quality result.

- [ ] **Step 2: Add export request VO**

Create `StoryboardExcelExportReqVO` with `Long episodeId` and `Long sceneId` query fields.

- [ ] **Step 3: Implement service workflow update**

In `StoryboardService`, add `updateItemWorkflow`. It must load the item, update only non-null fields from the request, persist with `updateItem`, and return the refreshed item.

- [ ] **Step 4: Implement Excel export service**

Use `cn.hutool.poi.excel.ExcelUtil.getWriter(true)` to write rows. Export at least: project/storyboard IDs, episode ID, scene ID, shot number, workflow mode, resolved mode, reason, shot type, duration, camera angle, camera movement, content, dialogue, sound, character IDs, scene asset item ID, prop IDs, storyboard image URL, 25-grid URL, action storyboard URL, key frame URLs, first frame URL, last frame URL, video prompt, generated video URL, quality check result, remark.

- [ ] **Step 5: Add controller endpoints**

Add `PUT /item/{id}/workflow` returning `CommonResult<StoryboardItem>`. Add Excel endpoint that sets `Content-Disposition: attachment; filename="storyboard-{id}.xlsx"` and returns `ResponseEntity<byte[]>`.

- [ ] **Step 6: Add frontend API methods**

Add:

```ts
updateWorkflow: (id: number, data: StoryboardWorkflowUpdateReq) =>
  http.put<never, StoryboardItem>(`/api/storyboard/item/${id}/workflow`, data)
```

Add `downloadExcel` using authenticated `fetch` or axios blob and an object URL.

- [ ] **Step 7: Verify**

Run: `cd ai-fusion-video && ./mvnw -q -DskipTests compile`

Expected: command exits 0.

---

### Task 3: Add AI Tooling and Prompt Files for Mode Workflow

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/UpdateStoryboardItemWorkflowToolExecutor.java`
- Create: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-mode-classifier.system.md`
- Create: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-narrative-expand.system.md`
- Create: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-action-expand.system.md`
- Create: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-prompt-gen.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-gen.system.md`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-executor.system.md`
- Modify: `ai-fusion-video-web/lib/api/ai-pipeline.ts`
- Modify: `ai-fusion-video-web/components/dashboard/shared/ai-task-display.ts`

**Interfaces:**
- Tool name: `update_storyboard_item_workflow`
- Agent types:
  - `storyboard_mode_classifier`
  - `storyboard_narrative_expand`
  - `storyboard_action_expand`
  - `storyboard_video_prompt_gen`

- [ ] **Step 1: Add workflow update tool executor**

Implement a tool executor that parses JSON input, builds `StoryboardWorkflowUpdateReqVO`, calls `storyboardService.updateItemWorkflow`, and returns status plus updated workflow fields.

- [ ] **Step 2: Add classifier prompt**

Prompt must classify each target shot as `narrative` or `action`, write `videoWorkflowResolvedMode` and `videoWorkflowReason`, and preserve user override when `videoWorkflowMode` is already `narrative` or `action`.

- [ ] **Step 3: Add narrative expand prompt**

Prompt must generate a 25-grid image from the story board image and explicitly state that it is not cutting the uploaded image into 25 pieces.

- [ ] **Step 4: Add action expand prompt**

Prompt must skip 25-grid generation and generate an action storyboard/motion plan focused on body positioning, sword paths, water, snow, close combat, and continuity.

- [ ] **Step 5: Add video prompt prompt**

Prompt must generate a 15-second video prompt. Narrative mode emphasizes clarity, evidence, emotion, and sequence. Action mode forbids in-image subtitles and one-move-one-stop pacing.

- [ ] **Step 6: Register frontend pipeline names**

Add the new agent types to `PIPELINE_AGENT_TYPES` and display names.

- [ ] **Step 7: Verify compile**

Run: `cd ai-fusion-video && ./mvnw -q -DskipTests compile`

Expected: command exits 0.

---

### Task 4: Add Frontend Mode Selection and Excel Download

**Files:**
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/page.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-table-view.tsx`
- Modify: `ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx`
- Modify: `ai-fusion-video-web/lib/api/storyboard.ts`

**Interfaces:**
- `onUpdateItemField(itemId, "videoWorkflowMode", value)` should update mode through the existing item update flow.
- `storyboardApi.downloadExcel(storyboardId, { episodeId, sceneId })` downloads Excel.

- [ ] **Step 1: Add mode column**

In the table, add a compact `模式` column. Display `自动`, `剧情`, or `战斗`. For first-phase simplicity, use a native `<select>` styled like existing controls.

- [ ] **Step 2: Add material status column**

Add a `故事板素材` column that shows whether the row has 25-grid, action storyboard, key frames, or optional first/last frames.

- [ ] **Step 3: Wire mode update**

When mode changes, call `onUpdateItemField(item.id, "videoWorkflowMode", nextMode)`.

- [ ] **Step 4: Add ref-panel batch actions**

Add buttons for `批量设为剧情`, `批量设为战斗`, and `批量恢复自动` for current scene items. Each loops over current items and updates `videoWorkflowMode`.

- [ ] **Step 5: Add Excel download button**

On the storyboard page top action area, add `下载分镜表` and call `storyboardApi.downloadExcel(storyboard.id, { episodeId: currentEpisodeId, sceneId: currentSceneId })`.

- [ ] **Step 6: Verify frontend lint**

Run: `cd ai-fusion-video-web && pnpm lint`

Expected: command exits 0.

---

### Task 5: Full Verification

**Files:**
- No new files.

**Interfaces:**
- Verifies all earlier task deliverables together.

- [ ] **Step 1: Backend compile**

Run: `cd ai-fusion-video && ./mvnw -q -DskipTests compile`

Expected: command exits 0.

- [ ] **Step 2: Frontend lint**

Run: `cd ai-fusion-video-web && pnpm lint`

Expected: command exits 0.

- [ ] **Step 3: Git review**

Run: `git diff --stat` and `git diff --check`.

Expected: `git diff --check` exits 0.
