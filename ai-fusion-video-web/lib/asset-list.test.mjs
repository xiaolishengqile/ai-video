import assert from "node:assert/strict";
import test from "node:test";

import { mergeAssetsById } from "./asset-list.mjs";

test("merges paginated assets once per id while preserving first-seen order", () => {
  const pageOne = [{ id: 1, name: "one" }, { id: 2, name: "original" }];
  const pageTwo = [{ id: 2, name: "duplicate" }, { id: 3, name: "three" }];

  assert.deepEqual(mergeAssetsById(pageOne, pageTwo), [pageOne[0], pageOne[1], pageTwo[1]]);
});
