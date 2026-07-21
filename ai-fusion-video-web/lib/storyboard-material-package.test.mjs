import test from "node:test";
import assert from "node:assert/strict";

import {
  buildMaterialPackageGenerationPlan,
  buildStoryboardGridGenerationPlans,
  buildVideoPromptGenerationPlan,
  getMissingMaterialPackageItemIds,
  getMissingVideoPromptItemIds,
  getStoryboardMaterialPackageStatus,
  summarizeMaterialPackages,
} from "./storyboard-material-package.mjs";

test("marks narrative packages complete only when assets, 25-grid, and prompt exist", () => {
  const complete = getStoryboardMaterialPackageStatus({
    id: 1,
    characterIds: "[10]",
    grid25ImageUrl: "/grid.png",
    videoPrompt: "prompt",
    videoWorkflowMode: "narrative",
  });

  assert.equal(complete.complete, true);
  assert.deepEqual(complete.missing, []);

  const missing = getStoryboardMaterialPackageStatus({
    id: 2,
    grid25ImageUrl: "/grid.png",
    videoWorkflowMode: "narrative",
  });

  assert.equal(missing.complete, false);
  assert.deepEqual(missing.missing, ["asset", "videoPrompt"]);
});

test("does not treat empty asset id arrays as linked assets", () => {
  const status = getStoryboardMaterialPackageStatus({
    id: 10,
    characterIds: "[]",
    sceneAssetItemIds: "[]",
    propIds: "[]",
    grid25ImageUrl: "/grid.png",
    videoPrompt: "prompt",
  });

  assert.equal(status.hasAssets, false);
  assert.deepEqual(status.missing, ["asset"]);
});

test("marks action packages complete only when assets, 4-grid, and prompt exist", () => {
  const complete = getStoryboardMaterialPackageStatus({
    id: 3,
    sceneAssetItemId: 88,
    actionStoryboardImageUrl: "/action.png",
    videoPrompt: "prompt",
  }, "action");

  assert.equal(complete.complete, true);
  assert.equal(complete.mode, "action");

  const missing = getStoryboardMaterialPackageStatus({
    id: 4,
    propIds: "[20]",
    videoPrompt: "prompt",
  }, "action");

  assert.deepEqual(missing.missing, ["actionStoryboard"]);
});

test("returns only incomplete material package and prompt ids", () => {
  const items = [
    { id: 1, characterIds: "[10]", grid25ImageUrl: "/grid.png", videoPrompt: "prompt" },
    { id: 2, characterIds: "[11]", grid25ImageUrl: "/grid.png" },
    { id: 3, videoPrompt: "prompt" },
    { id: 4, grid25ImageUrl: "/grid.png", videoPrompt: "prompt" },
  ];

  assert.deepEqual(getMissingMaterialPackageItemIds(items, "narrative"), [2, 3]);
  assert.deepEqual(getMissingVideoPromptItemIds(items), [2]);
});

test("summarizes material package completion", () => {
  const summary = summarizeMaterialPackages([
    { id: 1, characterIds: "[10]", grid25ImageUrl: "/grid.png", videoPrompt: "prompt" },
    { id: 2, characterIds: "[11]", videoPrompt: "prompt" },
    { id: 3, grid25ImageUrl: "/grid.png" },
  ], "narrative");

  assert.deepEqual(summary, {
    total: 3,
    complete: 1,
    missingAssets: 1,
    missingVisual: 1,
    missingPrompt: 1,
  });
});

test("builds material package plan with pending and skipped ids", () => {
  const plan = buildMaterialPackageGenerationPlan([
    { id: 1, characterIds: "[10]", grid25ImageUrl: "/grid.png", videoPrompt: "prompt" },
    { id: 2, characterIds: "[11]", grid25ImageUrl: "/grid.png" },
    { id: 3, characterIds: "[12]" },
  ], "narrative");

  assert.deepEqual(plan.pendingIds, [2, 3]);
  assert.deepEqual(plan.skippedIds, [1]);
  assert.equal(plan.label, "生成剧情素材包 (2 个镜头，跳过 1 个已完成)");
});

test("builds video prompt plan with pending and skipped ids", () => {
  const plan = buildVideoPromptGenerationPlan([
    { id: 1, videoPrompt: "prompt" },
    { id: 2 },
    { id: 3, videoPrompt: "prompt" },
  ], "AI生成视频提示词 · 场次 1");

  assert.deepEqual(plan.pendingIds, [2]);
  assert.deepEqual(plan.skippedIds, [1, 3]);
  assert.equal(plan.label, "AI生成视频提示词 · 场次 1 (1 个镜头，跳过 2 个已完成)");
});

test("splits storyboard grid generation plan by workflow mode", () => {
  const plans = buildStoryboardGridGenerationPlans([
    { id: 1, videoWorkflowMode: "narrative", duration: 15, grid25ImageUrl: "/grid.png", videoPrompt: "prompt" },
    { id: 2, videoWorkflowResolvedMode: "action", actionStoryboardImageUrl: "/action.png", motionPlan: "plan", videoPrompt: "prompt" },
    { id: 3, videoWorkflowMode: "narrative", duration: 15, videoPrompt: "prompt" },
    { id: 4, videoWorkflowMode: "action", actionStoryboardImageUrl: "/action.png" },
    { id: 5, videoWorkflowMode: "auto" },
  ], "全剧本");

  assert.equal(plans.totalPending, 2);
  assert.equal(plans.totalSkipped, 2);
  assert.deepEqual(plans.needsModeResolutionIds, [5]);
  assert.deepEqual(plans.narrative.pendingIds, [3]);
  assert.deepEqual(plans.action.pendingIds, [4]);
  assert.equal(plans.narrative.label, "全剧本 · 剧情宫格图 (1 个镜头，跳过 1 个已完成)");
  assert.equal(plans.action.label, "全剧本 · 战斗宫格图 (1 个镜头，跳过 1 个已完成)");
});

test("blocks short narrative grid items before generation", () => {
  const plans = buildStoryboardGridGenerationPlans([
    { id: 1, videoWorkflowMode: "narrative", duration: 8 },
    { id: 2, videoWorkflowMode: "narrative", duration: 12 },
    { id: 3, videoWorkflowMode: "action", actionStoryboardImageUrl: "/action.png" },
  ], "当前场次");

  assert.deepEqual(plans.blockedDurationIds, [1]);
  assert.deepEqual(plans.narrative.pendingIds, [2]);
  assert.deepEqual(plans.action.pendingIds, [3]);
  assert.equal(plans.totalBlocked, 1);
});

test("reports missing asset ids for grid preflight", () => {
  const plans = buildStoryboardGridGenerationPlans([
    { id: 1, videoWorkflowMode: "narrative", duration: 15 },
    { id: 2, videoWorkflowMode: "action", propIds: "[20]" },
  ], "当前场次");

  assert.deepEqual(plans.missingAssetIds, [1]);
});
