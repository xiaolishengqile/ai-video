/* eslint-disable @typescript-eslint/no-require-imports */
const assert = require("node:assert/strict");
const { readFile } = require("node:fs/promises");
const path = require("node:path");
const test = require("node:test");

test("global asset page selects every filtered result and reloads after deletion", async () => {
  const page = await readFile(path.join(__dirname, "page.tsx"), "utf8");

  assert.match(page, /mergeAssetsById/);
  assert.match(page, /loadingMoreRef/);
  assert.match(page, /const selectAllFiltered = async/);
  assert.match(page, /size: Math\.max\(total, PAGE_SIZE\)/);
  assert.match(page, /全选当前筛选结果/);
  assert.match(page, /await loadFirstPage\(\)/);
  assert.match(page, /max-w-\[320px\]/);
});
