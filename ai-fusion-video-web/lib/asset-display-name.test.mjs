import assert from "node:assert/strict";
import test from "node:test";

import { getAssetDisplayName } from "./asset-display-name.mjs";

test("shows only the primary asset name after category and episode prefixes", () => {
  assert.equal(getAssetDisplayName("角色图/第一集出现的人物/顾沉舟"), "顾沉舟");
  assert.equal(getAssetDisplayName("道具图/第 1 集/零枷·悼亡者"), "零枷·悼亡者");
});

test("keeps names without slash prefixes unchanged", () => {
  assert.equal(getAssetDisplayName("凌烬"), "凌烬");
});
