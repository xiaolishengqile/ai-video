import test from "node:test";
import assert from "node:assert/strict";

import { getStoryboardItemProgressLabel } from "./ai-task-progress-label.mjs";

test("extracts storyboard item id from structured tool arguments", () => {
  assert.equal(
    getStoryboardItemProgressLabel('{"storyboardItemId":2503}'),
    "镜头 #2503"
  );
});

test("extracts storyboard item id from sub agent message", () => {
  assert.equal(
    getStoryboardItemProgressLabel('{"message":"请生成素材\\nstoryboardItemId: 2504\\nprojectId: 18"}'),
    "镜头 #2504"
  );
});

test("returns null when arguments do not include storyboard item id", () => {
  assert.equal(getStoryboardItemProgressLabel('{"projectId":18}'), null);
});
