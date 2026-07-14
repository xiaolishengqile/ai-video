const assert = require("node:assert/strict");
const { readFile } = require("node:fs/promises");
const path = require("node:path");
const test = require("node:test");

test("project asset page wires selection mode to batch deletion", async () => {
  const page = await readFile(path.join(__dirname, "page.tsx"), "utf8");

  assert.match(page, /const \[selectionMode, setSelectionMode\]/);
  assert.match(page, /assetApi\.deleteBatch/);
  assert.match(page, /全选当前列表/);
});
