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
const generateImageTool = read("ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/GenerateImageToolExecutor.java");

assertContains(registry, 'toolName("generate_storyboard_narrative_material")', "narrative dispatcher");
assertContains(registry, 'refAgentType("storyboard_narrative_material_executor")', "narrative dispatcher");
assertContains(registry, 'type("storyboard_narrative_material_executor")', "narrative executor");
assertContains(registry, 'toolName("generate_storyboard_action_material")', "action dispatcher");
assertContains(registry, 'refAgentType("storyboard_action_material_executor")', "action dispatcher");
assertContains(registry, 'type("storyboard_action_material_executor")', "action executor");

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
