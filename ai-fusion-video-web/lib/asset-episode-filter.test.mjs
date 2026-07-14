import assert from "node:assert/strict";
import test from "node:test";

import { filterAssetsByEpisode, listAssetEpisodes } from "./asset-episode-filter.mjs";

const assets = [
  { id: 1, episodeNumber: 10 },
  { id: 2, episodeNumber: null },
  { id: 3, episodeNumber: 2 },
  { id: 4, episodeNumber: 10 },
];

test("lists distinct episode numbers in ascending order", () => {
  assert.deepEqual(listAssetEpisodes(assets), [2, 10]);
});

test("filters all, a specific episode, and unscoped assets", () => {
  assert.deepEqual(filterAssetsByEpisode(assets, undefined).map(({ id }) => id), [1, 2, 3, 4]);
  assert.deepEqual(filterAssetsByEpisode(assets, 10).map(({ id }) => id), [1, 4]);
  assert.deepEqual(filterAssetsByEpisode(assets, "unscoped").map(({ id }) => id), [2]);
});
