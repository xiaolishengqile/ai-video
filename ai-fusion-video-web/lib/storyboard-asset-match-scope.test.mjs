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

test("uses only the selected scene when matching current scene assets", () => {
  const scope = getStoryboardAssetMatchScope(groups, {
    type: "scene",
    episodeId: 1,
    sceneId: 11,
  });

  assert.deepEqual(scope.itemIds, [103, 104]);
  assert.equal(scope.scopeLabel, "当前场次");
});

test("keeps every item id when the selected episode has more than ten shots", () => {
  const scope = getStoryboardAssetMatchScope(groups, {
    type: "episode",
    episodeId: 2,
  });

  assert.deepEqual(scope.itemIds, [200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210]);
  assert.equal(scope.scopeLabel, "当前集");
});

test("deduplicates storyboard item ids without changing order", () => {
  const scope = getStoryboardAssetMatchScope(
    [{ scene: { id: 30, episodeId: 3 }, items: [{ id: 301 }, { id: 301 }, { id: 302 }] }],
    { type: "all" },
  );

  assert.deepEqual(scope.itemIds, [301, 302]);
  assert.equal(scope.scopeLabel, "当前分镜表");
});
