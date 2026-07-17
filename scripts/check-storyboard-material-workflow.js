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

assertContains(narrativePrompt, "可以同时调用多个子 Agent 实例并行处理不同镜头", "narrative prompt");
assertContains(actionPrompt, "可以同时调用多个子 Agent 实例并行处理不同镜头", "action prompt");
assertContains(actionPrompt, "4 宫格", "action prompt");

const narrativeExecutorPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-narrative-material-executor.system.md");
const actionExecutorPrompt = read("ai-fusion-video/src/main/resources/prompts/agents/storyboard-action-material-executor.system.md");

assertContains(narrativeExecutorPrompt, "最多重试 3 次", "narrative executor prompt");
assertContains(actionExecutorPrompt, "最多重试 3 次", "action executor prompt");
assertContains(narrativeExecutorPrompt, "generate_image", "narrative executor prompt");
assertContains(actionExecutorPrompt, "generate_image", "action executor prompt");
assertContains(actionExecutorPrompt, "4 宫格", "action executor prompt");
assertContains(registry, "4 宫格", "action material registry");

assertMatches(
  generateImageTool,
  /MAX_IMAGE_GENERATION_RETRIES\s*=\s*3/,
  "generate_image retry count"
);
assertContains(generateImageTool, "attempt <= MAX_IMAGE_GENERATION_RETRIES", "generate_image retry loop");

console.log("storyboard material workflow checks passed");
