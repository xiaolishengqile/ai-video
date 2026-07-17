import test from "node:test";
import assert from "node:assert/strict";

import { getStoryboardAssetMatchScope } from "./storyboard-asset-match-scope.mjs";

const groups = [
  {
    scene: { id: 10, episodeId: 1 },
    items: [{ id: 101 }, { id: 102 }],
  },
  {
    scene: { id: 11, episodeId: 1 },
    items: [{ id: 103 }, { id: 104 }],
  },
  {
    scene: { id: 20, episodeId: 2 },
    items: Array.from({ length: 11 }, (_, index) => ({ id: 200 + index })),
  },
];

test("matches every storyboard item even when a scene is selected", () => {
  const scope = getStoryboardAssetMatchScope(groups);

  assert.deepEqual(scope.itemIds, [101, 102, 103, 104, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210]);
  assert.equal(scope.scopeLabel, "全部镜头");
});

test("matches every storyboard item even when an episode is selected", () => {
  const scope = getStoryboardAssetMatchScope(groups);

  assert.deepEqual(scope.itemIds, [101, 102, 103, 104, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210]);
  assert.equal(scope.scopeLabel, "全部镜头");
});

test("deduplicates storyboard item ids without changing order", () => {
  const scope = getStoryboardAssetMatchScope(
    [{ scene: { id: 30, episodeId: 3 }, items: [{ id: 301 }, { id: 301 }, { id: 302 }] }],
  );

  assert.deepEqual(scope.itemIds, [301, 302]);
  assert.equal(scope.scopeLabel, "全部镜头");
});
