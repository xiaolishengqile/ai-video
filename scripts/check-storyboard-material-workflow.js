#!/usr/bin/env node

const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const read = (relativePath) =>
  fs.readFileSync(path.join(root, relativePath), "utf8");

function assertContains(text, expected, label) {
  if (!text.includes(expected)) {
    throw new Error(`${label} missing: ${expected}`);
  }
}

function assertMatches(text, pattern, label) {
  if (!pattern.test(text)) {
    throw new Error(`${label} did not match ${pattern}`);
  }
}

const registry = read("ai-fusion-video/src/main/java/com/stonewu/fusion/config/ai/AiAgentRegistry.java");
const narrativePrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-narrative-expand.system.md");
const actionPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-action-expand.system.md");
const scriptFullParsePrompt = read("ai-fusion-video/src/main/resources/prompts/agents/script-full-parse.system.md");
const scriptToStoryboardPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/script-to-storyboard.system.md");
const generateImageTool = read("ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/GenerateImageToolExecutor.java");
const storyboardPage = read("ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/page.tsx");
const pipelineApi = read("ai-fusion-video-web/lib/api/ai-pipeline.ts");
const scriptFullParseResumeStrategy = read("ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/ScriptFullParseResumeStrategy.java");
const scriptToStoryboardResumeStrategy = read("ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/ScriptToStoryboardResumeStrategy.java");
const assetMatchPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-asset-match.system.md");
const videoPromptGenPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-prompt-gen.system.md");
const videoGenPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-gen.system.md");
const videoExecutorPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-video-executor.system.md");
const refPanel = read("ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-ref-panel.tsx");
const videoDialog = read("ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/video-gen-dialog.tsx");
const tableView = read("ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-table-view.tsx");
const cardView = read("ai-fusion-video-web/app/(dashboard)/projects/[id]/storyboards/_components/storyboard-card-view.tsx");
const materialPackageHelper = read("ai-fusion-video-web/lib/storyboard-material-package.mjs");

assertContains(registry, 'toolName("generate_storyboard_narrative_material")', "narrative dispatcher");
assertContains(registry, 'refAgentType("storyboard_narrative_material_executor")', "narrative dispatcher");
assertContains(registry, 'type("storyboard_narrative_material_executor")', "narrative executor");
assertContains(registry, 'toolName("generate_storyboard_action_material")', "action dispatcher");
assertContains(registry, 'refAgentType("storyboard_action_material_executor")', "action dispatcher");
assertContains(registry, 'type("storyboard_action_material_executor")', "action executor");
assertContains(registry, 'type("storyboard_asset_matcher")', "asset matcher agent");
assertContains(registry, 'type("storyboard_asset_match_executor")', "asset match executor agent");
assertContains(registry, '"update_storyboard_item_assets"', "asset update tool registration");
assertContains(storyboardPage, 'agentType: "storyboard_asset_matcher"', "asset match button pipeline");
assertContains(storyboardPage, "AI匹配资产", "asset match button");
assertContains(pipelineApi, '"storyboard_asset_matcher"', "asset matcher pipeline type");
assertContains(assetMatchPrompt, "每轮最多同时调用 5 个子 Agent", "asset match concurrency limit");
assertContains(assetMatchPrompt, "429", "asset match rate limit handling");
assertContains(assetMatchPrompt, "降级为每轮最多 2 个", "asset match rate limit fallback");
assertContains(scriptFullParsePrompt, "每轮最多同时调用 3 个 `episode_scene_writer`", "script parse episode writer concurrency limit");
assertContains(refPanel, 'agentType: "storyboard_video_prompt_gen"', "storyboard prompt-only pipeline");
if (/agentType:\s*"storyboard_video_gen"/.test(refPanel + storyboardPage)) {
  throw new Error("storyboard UI should not start storyboard_video_gen");
}
if (/generate_video/.test(
  registry.match(/private void registerStoryboardVideoExecutorAgent\(\)[\s\S]*?\.build\(\);\n        \}/)?.[0] || ""
)) {
  throw new Error("storyboard video executor should not register generate_video");
}
assertContains(videoPromptGenPrompt, "参考优先级", "video prompt narrative template");
assertContains(videoPromptGenPrompt, "15秒严格时间轴", "video prompt timeline template");
assertContains(videoPromptGenPrompt, "体型与高度锁定", "video prompt action template");
assertContains(videoPromptGenPrompt, "特效层级锁定", "video prompt action effects");
assertContains(videoPromptGenPrompt, "核心任务", "video prompt action sb10 core task");
assertContains(videoPromptGenPrompt, "最终成片画风｜最高优先级", "video prompt action sb10 visual priority");
assertContains(videoPromptGenPrompt, "机械物理要求", "video prompt action sb10 physics");
assertContains(videoPromptGenPrompt, "强制负面提示词", "video prompt action sb10 negatives");
assertContains(videoPromptGenPrompt, "不调用 generate_video", "video prompt no generation");
assertContains(videoGenPrompt, "只生成视频提示词", "video gen prompt-only fallback");
assertContains(videoExecutorPrompt, "不得调用 `generate_video`", "video executor no generation");
assertContains(videoDialog, "生成提示词", "video dialog prompt copy");
assertContains(materialPackageHelper, "getMissingMaterialPackageItemIds", "material package missing helper");
assertContains(materialPackageHelper, "summarizeMaterialPackages", "material package summary helper");
assertContains(refPanel, "素材包完成度", "material package completion UI");
assertContains(refPanel, "getMissingMaterialPackageItemIds(sceneGroup.items, mode)", "material package missing rerun");
assertContains(videoDialog, "getMissingVideoPromptItemIds(items)", "prompt dialog missing default");
assertContains(tableView, "复制视频提示词", "table prompt copy button");
assertContains(cardView, "复制视频提示词", "card prompt copy button");

const scriptFullParseRegistry = registry.match(/private void registerScriptFullParseAgent\(\)[\s\S]*?private void registerScriptStoryToScriptAgent\(\)/)?.[0] || "";
if (/run_script_asset_prebinding|resolve_scene_entity_manifest|create_project_asset_catalog_snapshot/.test(scriptFullParseRegistry)) {
  throw new Error("script_full_parse should not depend on prebinding, snapshots, or entity manifest resolution");
}
if (/assetCatalogSnapshotId/.test(scriptToStoryboardPrompt)) {
  throw new Error("storyboard generation should not require asset catalog snapshots");
}
if (/run_script_asset_prebinding|resolve_scene_entity_manifest|create_project_asset_catalog_snapshot/.test(scriptFullParsePrompt)) {
  throw new Error("script parsing prompt should stay lightweight");
}
if (/run_script_asset_prebinding|create_project_asset_catalog_snapshot|资产预绑定|资产快照|快照/.test(scriptFullParseResumeStrategy)) {
  throw new Error("script parse resume plan should not include removed asset prebinding or snapshot steps");
}
if (/storyboard_asset_preprocessor|create_project_asset_catalog_snapshot|分镜资产预处理|资产快照/.test(scriptToStoryboardResumeStrategy)) {
  throw new Error("storyboard resume plan should not include removed asset preprocessor or snapshot steps");
}

assertContains(narrativePrompt, "可以同时调用多个子 Agent 实例并行处理不同镜头", "narrative prompt");
assertContains(actionPrompt, "可以同时调用多个子 Agent 实例并行处理不同镜头", "action prompt");
assertContains(actionPrompt, "4 宫格", "action prompt");

const narrativeExecutorPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-narrative-material-executor.system.md");
const actionExecutorPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-action-material-executor.system.md");

assertContains(narrativeExecutorPrompt, "最多重试 3 次", "narrative executor prompt");
assertContains(actionExecutorPrompt, "最多重试 3 次", "action executor prompt");
assertContains(narrativeExecutorPrompt, "generate_image", "narrative executor prompt");
assertContains(actionExecutorPrompt, "generate_image", "action executor prompt");
assertContains(narrativeExecutorPrompt, "update_storyboard_item_video", "narrative video prompt save");
assertContains(actionExecutorPrompt, "update_storyboard_item_video", "action video prompt save");
assertContains(narrativeExecutorPrompt, "参考优先级", "narrative material video prompt template");
assertContains(actionExecutorPrompt, "体型与高度锁定", "action material video prompt template");
assertContains(actionExecutorPrompt, "核心任务", "action material sb10 core task");
assertContains(actionExecutorPrompt, "最终成片画风｜最高优先级", "action material sb10 visual priority");
assertContains(actionExecutorPrompt, "机械物理要求", "action material sb10 physics");
assertContains(actionExecutorPrompt, "强制负面提示词", "action material sb10 negatives");
assertContains(actionExecutorPrompt, "4 宫格", "action executor prompt");
assertContains(registry, "4 宫格", "action material registry");

assertMatches(
  generateImageTool,
  /MAX_IMAGE_GENERATION_RETRIES\s*=\s*3/,
  "generate_image retry count"
);
assertContains(generateImageTool, "attempt <= MAX_IMAGE_GENERATION_RETRIES", "generate_image retry loop");

console.log("storyboard material workflow checks passed");
