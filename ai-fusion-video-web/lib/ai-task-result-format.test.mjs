import test from "node:test";
import assert from "node:assert/strict";

import {
  formatAiTaskResultValue,
  getAiTaskResultFieldLabel,
} from "./ai-task-result-format.mjs";

test("translates storyboard workflow result fields", () => {
  assert.equal(getAiTaskResultFieldLabel("storyboardItemId"), "镜头ID");
  assert.equal(getAiTaskResultFieldLabel("videoWorkflowMode"), "视频工作流模式");
  assert.equal(getAiTaskResultFieldLabel("videoWorkflowResolvedMode"), "实际采用模式");
  assert.equal(getAiTaskResultFieldLabel("hasGrid25"), "已有25宫格图");
});

test("translates workflow enum values", () => {
  assert.equal(formatAiTaskResultValue("auto"), "自动");
  assert.equal(formatAiTaskResultValue("narrative"), "剧情");
  assert.equal(formatAiTaskResultValue("action"), "战斗");
});

test("keeps regular values readable", () => {
  assert.equal(formatAiTaskResultValue(true), "是");
  assert.equal(formatAiTaskResultValue(false), "否");
  assert.equal(formatAiTaskResultValue(["a", "b"]), "[2 项]");
});
